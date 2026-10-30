package com.mizhii.ledger.data.import

import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.TxnSource
import com.mizhii.ledger.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 微信支付账单（xlsx）解析回归。
 *
 * fixture `bills/wechat_bill_sample.xlsx` 是**合成数据**：商户、单号、金额全部虚构，
 * 但完整复刻了微信「下载账单」导出的真实格式 —— 包括：
 *
 *  - 表头前有一段说明区，真正的表头不在第 1 行；
 *  - 交易时间是 **Excel 序列号**，不是文本；
 *  - 金额(元) 是裸数字（`5`、`200.2`），不是 `¥5.00`；
 *  - 中性交易（提现）的「收/支」列是 `/`；
 *  - 退款是独立的一行收入，原支出行状态变成「已全额退款」。
 */
class BillFileTest {

    private val fixture = File("src/test/resources/bills/wechat_bill_sample.xlsx")

    private val parsed: BillFile.Parsed by lazy {
        assertTrue("账单 fixture 缺失: ${fixture.absolutePath}", fixture.exists())
        BillFile.parse(BillFile.readTable(fixture.name, fixture.readBytes()))
    }

    // ── 容器与表头 ─────────────────────────────────────────────────

    @Test
    fun `xlsx 能读出表头与全部数据行`() {
        val table = BillFile.readTable(fixture.name, fixture.readBytes())
        assertEquals("xlsx", table.container)
        assertEquals("XLSX", table.encoding)

        val p = BillFile.parse(table)
        // 10 行说明区 + 1 行表头 + 12 行数据
        assertEquals(23, table.rows.size)
        assertEquals(BillFile.Kind.WECHAT, p.kind)
        assertEquals(Channel.WECHAT, p.channel)
        assertEquals("表头不在第一行", 10, p.headerRowIndex)

        val header = table.rows[p.headerRowIndex]
        assertEquals("交易时间", header[0].trim())
        assertEquals("收/支", header[4].trim())
        assertEquals("交易单号", header[8].trim())

        assertEquals("12 笔里 10 笔收支 + 2 笔中性", 10, p.entries.size)
        assertEquals(2, p.neutral.size)
        assertEquals("表头以下不应有无法解析的行", 0, p.unparsedRowCount)
    }

    @Test
    fun `账单声明的汇总与实际解析完全一致`() {
        val s = parsed.summary
        assertEquals(12, s.totalCount)
        assertEquals(3, s.incomeCount)
        assertEquals(301_414L, s.incomeCents)
        assertEquals(7, s.expenseCount)
        assertEquals(17_074L, s.expenseCents)
        assertEquals(2, s.neutralCount)
        assertEquals(200_000L, s.neutralCents)

        val problems = parsed.reconcileProblems()
        assertEquals("对账必须零差异，实际差异：$problems", 0, problems.size)
    }

    @Test
    fun `xlsx 的日期是 Excel 序列号且按 UTC+08 解释`() {
        // 46324.720034722224 == 2026-10-29 17:16:51（Asia/Shanghai）
        val millis = BillFile.parseTime("46324.720034722224")
        assertNotNull(millis)
        assertEquals("2026-10-29", Dates.dayKey(millis!!))
        assertEquals("17:16", Dates.timeLabel(millis))
    }

    @Test
    fun `xlsx 的金额是裸数字`() {
        assertEquals(500L, BillFile.parseMoney("5"))
        assertEquals(20_020L, BillFile.parseMoney("200.2"))
        assertEquals(174L, BillFile.parseMoney("1.74"))
        assertNull("斜杠不是金额", BillFile.parseMoney("/"))
    }

    // ── 逐笔语义 ───────────────────────────────────────────────────

    private fun find(counterparty: String, amountCents: Long): BillFile.Entry {
        val e = parsed.entries.firstOrNull { it.counterparty == counterparty && it.amountCents == amountCents }
        assertNotNull("找不到 $counterparty $amountCents 分的记录", e)
        return e!!
    }

    @Test
    fun `第一笔 扫二维码付款 字段完整`() {
        val e = parsed.entries.first()
        assertEquals("2026-10-29", Dates.dayKey(e.occurredAt))
        assertEquals("17:16", Dates.timeLabel(e.occurredAt))
        assertEquals(Direction.EXPENSE, e.direction)
        assertEquals(1_250L, e.amountCents)
        assertEquals("示例便利店A", e.counterparty)
        assertEquals("扫二维码付款", e.tradeType)
        assertEquals("零钱", e.payMethod)
        assertEquals("已转账", e.status)
        assertEquals("WX000000000000000000000001", e.externalNo)
        assertEquals(false, e.isRefund)
    }

    @Test
    fun `二维码收款是真实收款码收入`() {
        // 收款码收入，账单里带付款方名字，因此不需要强制待确认
        val e = find("示例付款人C", 1_240L)
        assertEquals(Direction.INCOME, e.direction)
        assertEquals("二维码收款", e.tradeType)
        assertEquals("已收钱", e.status)

        val candidate = e.toCandidate(Channel.WECHAT)
        assertEquals(TxnSource.CSV, candidate.source)
        assertFalse("账单里有付款方，不需要强制待确认", candidate.forcePending)
        assertEquals(Channel.WECHAT, candidate.channel)
    }

    @Test
    fun `转账收入归到往来款`() {
        val e = find("家人示例D", 300_000L)
        assertEquals(Direction.INCOME, e.direction)
        val candidate = e.toCandidate(Channel.WECHAT)
        assertEquals("transfer_in", candidate.suggestedCategoryId)
    }

    @Test
    fun `退款是独立的一行收入且归到 refund`() {
        val found = parsed.entries.firstOrNull { it.isRefund && it.direction == Direction.INCOME }
        assertNotNull(found)
        val e = found!!
        assertEquals("示例商户B-退款", e.tradeType)
        val candidate = e.toCandidate(Channel.WECHAT)
        assertEquals("refund", candidate.suggestedCategoryId)
        assertTrue("退款性质明确，不应强制待确认", !candidate.forcePending)
    }

    @Test
    fun `原支出即使已退款也仍是一笔支出`() {
        // 微信把退款记成独立一行，原支出行的状态变成「已全额退款」，但钱确实先付出去了。
        // 账本要的是两条：支出 1.74 + 收入(退款) 1.74，净额为 0，而不是把支出删掉。
        val found = parsed.entries.firstOrNull {
            it.tradeType == "商户消费" && it.status == "已全额退款"
        }
        assertNotNull(found)
        val e = found!!
        assertEquals(Direction.EXPENSE, e.direction)
        assertTrue(e.isRefund)
    }

    @Test
    fun `零钱提现是中性交易不计收支`() {
        assertEquals(2, parsed.neutral.size)
        for (e in parsed.neutral) {
            assertNull("中性交易 direction 必须为 null", e.direction)
            assertEquals("零钱提现", e.tradeType)
            assertEquals("示例银行(0000)", e.counterparty)
        }
        assertEquals(200_000L, parsed.neutral.sumOf { it.amountCents })
    }

    @Test
    fun `所有收支行都能转成候选流水`() {
        for (e in parsed.entries) {
            val c = e.toCandidate(Channel.WECHAT)
            assertTrue("金额必须为正", c.amountCents > 0)
            assertNotNull("必须有发生时间", c.occurredAt)
            assertEquals(TxnSource.CSV, c.source)
        }
    }

    // ── CSV 路径（列名映射用内联样例锁定）────────────────────────────

    @Test
    fun `CSV 能识别表头并解析支付宝列名`() {
        val csv = """
            支付宝交易记录明细查询
            账号:[test@example.com]
            起始日期:[2026-09-29 00:00:00]    终止日期:[2026-10-29 19:32:50]
            ---------------------------------交易记录明细列表------------------------------------
            交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
            2026-10-29 12:00:00,餐饮美食,示例便利店,shop@example.com,便利店购物,支出,4.50,余额宝,交易成功,2026102922001,20261029abc,-
            2026-10-28 09:30:00,转账红包,示例对方,zhang@example.com,转账,收入,100.00,余额,交易成功,2026102822002,20261028def,-
        """.trimIndent()

        val table = BillFile.readTable("alipay.csv", csv.toByteArray(Charsets.UTF_8))
        assertEquals("csv", table.container)
        assertEquals("UTF-8", table.encoding)

        val p = BillFile.parse(table)
        assertEquals(BillFile.Kind.ALIPAY, p.kind)
        assertEquals(Channel.ALIPAY, p.channel)
        assertEquals(2, p.entries.size)

        val expense = p.entries[0]
        assertEquals(450L, expense.amountCents)
        assertEquals(Direction.EXPENSE, expense.direction)
        assertEquals("示例便利店", expense.counterparty)
        assertEquals("便利店购物", expense.goods)
        assertEquals("余额宝", expense.payMethod)
        assertEquals("交易成功", expense.status)
        assertEquals("2026102922001", expense.externalNo)

        val income = p.entries[1]
        assertEquals(Direction.INCOME, income.direction)
        assertEquals(10_000L, income.amountCents)
    }

    @Test
    fun `解析器建议分类不应被文本规则覆盖`() {
        // Categorizer 优先 suggestedCategoryId（确定性证据）而不是文本规则（猜商户名）。
        // 这里锁住账单侧要喂给它的东西，防止回归成"退款被规则归到别的类"。
        val refund = parsed.entries.first { it.tradeType == "示例商户B-退款" }
        assertEquals("refund", refund.toCandidate(Channel.WECHAT).suggestedCategoryId)

        val transfer = parsed.entries.first { it.counterparty == "家人示例D" }
        assertEquals("transfer_in", transfer.toCandidate(Channel.WECHAT).suggestedCategoryId)

        // 普通消费不该有建议分类 —— 交给规则引擎
        val normal = parsed.entries.first { it.tradeType == "商户消费" && !it.isRefund }
        assertNull(normal.toCandidate(Channel.WECHAT).suggestedCategoryId)
    }

    @Test
    fun `CSV 里的引号 换行 逗号都要正确解析`() {
        val csv = "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\r\n" +
            "2026-10-29 12:00:00,商户消费,\"店名,含逗号\",\"多行\n商品\",支出,12.5,零钱,支付成功,450001,88001,\"备注里有\"\"引号\"\"\"\r\n"

        val rows = CsvReader.read(csv.toByteArray(Charsets.UTF_8)).rows
        val p = BillFile.parse(BillFile.Table(rows, "UTF-8", "csv"))
        assertEquals(1, p.entries.size)
        val e = p.entries[0]
        assertEquals("店名,含逗号", e.counterparty)
        assertEquals("多行\n商品", e.goods)
        assertEquals("备注里有\"引号\"", e.remark)
        assertEquals(1_250L, e.amountCents)
    }

    @Test
    fun `GBK 编码的 CSV 能被识别`() {
        val csv = "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\r\n" +
            "2026-10-29 12:00:00,商户消费,示例便利店,矿泉水,支出,3.5,零钱,支付成功,450001,88001,/\r\n"
        val bytes = csv.toByteArray(java.nio.charset.Charset.forName("GB18030"))

        val table = BillFile.readTable("wechat.csv", bytes)
        assertEquals("GB18030", table.encoding)
        val p = BillFile.parse(table)
        assertEquals(1, p.entries.size)
        assertEquals("示例便利店", p.entries[0].counterparty)
        assertEquals(350L, p.entries[0].amountCents)
    }

    @Test
    fun `找不到表头要明确报错而不是静默返回空`() {
        val bad = "这不是账单文件\n只有两行文字".toByteArray(Charsets.UTF_8)
        val table = BillFile.readTable("bad.csv", bad)
        val error = runCatching { BillFile.parse(table) }.exceptionOrNull()
        assertNotNull("必须抛异常", error)
        assertTrue("异常信息应说明缺什么", error!!.message!!.contains("表头"))
    }

    @Test
    fun `日期解析兼容 CSV 与 xlsx 两种形态`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val expected = LocalDateTime.of(2026, 10, 29, 17, 16, 51)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, BillFile.parseTime("2026-10-29 17:16:51"))
        assertEquals(expected, BillFile.parseTime("2026/10/29 17:16:51"))
        assertEquals(expected, BillFile.parseTime("46324.720034722224"))
        assertNull(BillFile.parseTime("不是时间"))
    }
}

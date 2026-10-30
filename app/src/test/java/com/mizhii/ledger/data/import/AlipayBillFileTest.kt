package com.mizhii.ledger.data.import

import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 支付宝账单（csv）解析回归。
 *
 * fixture `bills/alipay_bill_sample.csv` 是**合成数据**（商户/单号/金额全部虚构），
 * 但复刻了支付宝导出文件的真实格式差异，这个测试就是用来锁住它们的：
 *
 *  1. 编码是 **GB18030**（无 BOM），不是微信的 UTF-8-BOM
 *  2. 中性交易叫 **「不计收支」**，微信叫「中性交易」；
 *     而「不计收支：3笔 3000.00元」里含子串「支出：」—— R_EXPENSE 不加边界
 *     就会把这几笔当成支出
 *  3. 交易订单号/商家订单号后面带 **Tab**，必须 trim 掉才干净
 *  4. 存在 ¥0.00 的「不计收支」记录（芝麻免押），要计入笔数但不产生账目
 *
 * 另外支付宝账单自带 `交易分类`（餐饮美食/日用百货/信用借还…），
 * 比微信的 `交易类型` 信息量更大，是自动分类的确定性信号。
 */
class AlipayBillFileTest {

    private val fixture = File("src/test/resources/bills/alipay_bill_sample.csv")

    private val parsed: BillFile.Parsed by lazy {
        assertTrue("支付宝账单 fixture 缺失: ${fixture.absolutePath}", fixture.exists())
        BillFile.parse(BillFile.readTable(fixture.name, fixture.readBytes()))
    }

    @Test
    fun `GB18030 编码与表头定位`() {
        val table = BillFile.readTable(fixture.name, fixture.readBytes())
        assertEquals("csv", table.container)
        assertEquals("GB18030", table.encoding)

        val p = BillFile.parse(table)
        assertEquals(BillFile.Kind.ALIPAY, p.kind)
        assertEquals(Channel.ALIPAY, p.channel)

        val header = table.rows[p.headerRowIndex]
        assertEquals("交易时间", header[0].trim())
        assertEquals("收/支", header[5].trim())
        assertEquals("交易订单号", header[9].trim())

        // 12 = 收入 2 + 支出 7 + 不计收支 3
        assertEquals(9, p.entries.size)
        assertEquals(3, p.neutral.size)
        assertEquals("不应有无法解析的行", 0, p.unparsedRowCount)
    }

    @Test
    fun `账单声明的汇总与实际解析完全一致`() {
        val s = parsed.summary
        assertEquals(12, s.totalCount)
        assertEquals(2, s.incomeCount)
        assertEquals(52_000L, s.incomeCents)
        assertEquals(7, s.expenseCount)
        assertEquals(118_634L, s.expenseCents)
        assertEquals("支付宝把中性交易叫「不计收支」", 3, s.neutralCount)
        assertEquals(300_000L, s.neutralCents)

        val problems = parsed.reconcileProblems()
        assertEquals("对账必须零差异，实际差异：$problems", 0, problems.size)
    }

    @Test
    fun `不计收支不会被误当成支出`() {
        // 「不计收支：3笔 3000.00元」含子串「支出：」。
        // 若 R_EXPENSE 没有边界，支出会被算成 7+3=10 笔。
        val c = parsed.computed()
        assertEquals(7, c.expenseCount)
        assertEquals(3, c.neutralCount)
    }

    @Test
    fun `第一笔支出字段完整且订单号已 trim`() {
        val e = parsed.entries.first()
        assertEquals("2026-10-29", Dates.dayKey(e.occurredAt))
        assertEquals("17:13", Dates.timeLabel(e.occurredAt))
        assertEquals(Direction.EXPENSE, e.direction)
        assertEquals(450L, e.amountCents)
        assertEquals("示例便利店有限公司", e.counterparty)
        assertEquals("示例商品K", e.goods)
        assertEquals("示例银行储蓄卡(0000)", e.payMethod)
        assertEquals("交易成功", e.status)
        assertEquals("日用百货", e.tradeType)

        val externalNo = e.externalNo
        assertTrue("订单号不能为空", !externalNo.isNullOrBlank())
        assertEquals("订单号尾部不能带 Tab", externalNo, externalNo!!.trim())
        assertEquals("AL20261029220014000000000001", externalNo)
    }

    @Test
    fun `信用借还属于不计收支`() {
        assertEquals(3, parsed.neutral.size)
        assertTrue(parsed.neutral.all { it.direction == null })
        assertEquals("信用借还", parsed.neutral.first().tradeType)
        assertEquals(300_000L, parsed.neutral.sumOf { it.amountCents })
    }

    @Test
    fun `零元的不计收支记录计入条数但不产生账目`() {
        // 支付宝真实存在「芝麻免押下单成功 / 解冻成功」的 ¥0.00 记录（充电宝租借）。
        // 它们计入账单声明的总笔数，丢掉就永远对不上账；但显然不能变成一笔 ¥0 账目。
        val zero = parsed.neutral.filter { it.amountCents == 0L }
        assertEquals(2, zero.size)
        assertTrue(zero.all { it.direction == null })
        assertTrue(zero.all { it.tradeType == "信用借还" })
    }

    @Test
    fun `转账红包收入归到往来款`() {
        val incomes = parsed.entries.filter { it.direction == Direction.INCOME }
        assertEquals(2, incomes.size)
        assertEquals(52_000L, incomes.sumOf { it.amountCents })

        val first = incomes.first()
        assertEquals("转账红包", first.tradeType)
        assertEquals("示例对方M", first.counterparty)
        assertEquals("账户余额", first.payMethod)

        val candidate = first.toCandidate(Channel.ALIPAY)
        assertEquals("transfer_in", candidate.suggestedCategoryId)
        assertTrue("账单里有对方名，不需强制待确认", !candidate.forcePending)
    }

    @Test
    fun `交易分类字段可用于后续自动分类`() {
        // 支付宝的交易分类是真实品类（不是 WeChat 那种「商户消费/扫二维码付款」的动作类型）
        val categories = parsed.entries.mapNotNull { it.tradeType }.toSet()
        assertTrue("应包含餐饮美食", categories.contains("餐饮美食"))
        assertTrue("应包含日用百货", categories.contains("日用百货"))
        assertTrue("应包含医疗健康", categories.contains("医疗健康"))
    }

    // ── 品类 → 分类映射 ───────────────────────────────────────────

    @Test
    fun `支付宝交易分类能自动归类绝大多数支出`() {
        val expenses = parsed.entries.filter { it.direction == Direction.EXPENSE }
        assertEquals(7, expenses.size)

        val covered = expenses.filter { it.toCandidate(Channel.ALIPAY).suggestedCategoryId != null }
        assertEquals("合成的 7 笔支出都应命中品类映射，实际 ${covered.size}/7", 7, covered.size)

        // 且不能全是 other_expense
        val distinct = covered.mapNotNull { it.toCandidate(Channel.ALIPAY).suggestedCategoryId }.toSet()
        assertTrue("应映射出多个分类，实际 $distinct", distinct.size >= 6)
    }

    @Test
    fun `品类映射到正确的分类 id`() {
        fun cat(tradeType: String?, dir: Direction) = AlipayTradeCategory.categoryOf(tradeType, dir)

        assertEquals("food", cat("餐饮美食", Direction.EXPENSE))
        assertEquals("daily", cat("日用百货", Direction.EXPENSE))
        assertEquals("medical", cat("医疗健康", Direction.EXPENSE))
        assertEquals("entertainment", cat("文化休闲", Direction.EXPENSE))
        assertEquals("transport", cat("爱车养车", Direction.EXPENSE))
        assertEquals("communication", cat("充值缴费", Direction.EXPENSE))
        assertEquals("education", cat("教育培训", Direction.EXPENSE))
        assertEquals("shopping", cat("数码电器", Direction.EXPENSE))
        // 收入侧方向不同 → 同一个「转账红包」两种映射
        assertEquals("transfer_in", cat("转账红包", Direction.INCOME))
        assertEquals("transfer_out", cat("转账红包", Direction.EXPENSE))
        // 信用借还/账户存取是不计收支的，不该猜分类
        assertNull(cat("信用借还", Direction.EXPENSE))
        assertNull(cat("账户存取", Direction.EXPENSE))
        // 未知品类不猜
        assertNull(cat("不存在的品类", Direction.EXPENSE))
        assertNull(cat(null, Direction.EXPENSE))
    }

    @Test
    fun `微信账单不会套用支付宝品类映射`() {
        // 同一行数据走 WECHAT 渠道时不该拿到支付宝的品类映射
        val expense = parsed.entries.first { it.tradeType == "餐饮美食" }
        assertNull(expense.toCandidate(Channel.WECHAT).suggestedCategoryId)
        assertNotNull(expense.toCandidate(Channel.ALIPAY).suggestedCategoryId)
    }
}

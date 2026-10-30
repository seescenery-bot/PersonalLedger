package com.mizhii.ledger.domain.capture

import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.TxnSource
import com.mizhii.ledger.domain.model.DiscardReason
import com.mizhii.ledger.domain.model.ParseOutcome
import com.mizhii.ledger.domain.model.TxnCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通道 A 解析器测试（需求文档 §6.8.1 + §6.2.7 真机样本）。
 * 这些用例是 M2 的验收门槛，不允许跳过。
 */
class NotificationParserTest {

    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private val postTime = 1_793_001_600_000L

    private fun parse(pkg: String, title: String, body: String) =
        NotificationParser.parse(pkg, title, body, postTime)

    private fun expectSuccess(pkg: String, title: String, body: String): TxnCandidate {
        val o = parse(pkg, title, body)
        assertTrue("期望解析成功，实际是 $o", o is ParseOutcome.Success)
        return (o as ParseOutcome.Success).candidate
    }

    private fun expectReason(pkg: String, title: String, body: String): String {
        val o = parse(pkg, title, body)
        assertTrue("期望被丢弃，实际是 $o", o is ParseOutcome.Discarded)
        return (o as ParseOutcome.Discarded).reason
    }

    // ── 归一化（§6.2.3）──────────────────────────────────────────────

    @Test
    fun `归一化剥掉聚合前缀与重复账号名`() {
        assertEquals("已支付¥0.01", NotificationParser.normalize("[2条]微信支付: 已支付¥0.01"))
        assertEquals("已支付¥0.01", NotificationParser.normalize("[99条]微信支付：已支付¥0.01"))
        assertEquals("你已成功付款12.00元", NotificationParser.normalize("支付宝: 你已成功付款12.00元"))
        assertEquals("普通文本", NotificationParser.normalize("普通文本"))
        assertEquals("", NotificationParser.normalize(null))
    }

    // ── 真机样本（§6.2.7）──────────────────────────────────────────

    @Test
    fun `真机微信支付通知`() {
        // 实测原文：[2条]微信支付: 已支付¥0.01
        val c = expectSuccess(wechat, "微信支付", "[2条]微信支付: 已支付¥0.01")
        assertEquals(Channel.WECHAT, c.channel)
        assertEquals(Direction.EXPENSE, c.direction)
        assertEquals(1L, c.amountCents)
        assertNull("通知里没有商户名，必须为 null", c.counterparty)
        assertEquals(TxnSource.NOTIFICATION, c.source)
        assertEquals("已支付¥0.01", c.rawText)
    }

    @Test
    fun `真机微信支付通知的置信度应落在待确认区`() {
        val c = expectSuccess(wechat, "微信支付", "[2条]微信支付: 已支付¥0.01")
        val score = ConfidenceScorer.score(c, hasClearSemantic = true)
        assertEquals(60, score)
        assertTrue("60 分应进待确认，不应被丢弃", !ConfidenceScorer.shouldDiscard(score))
        assertTrue("60 分不应直接入账（默认阈值 70）", !ConfidenceScorer.isConfirmed(score, 70))
    }

    // ── 真机反例（支付宝还款提醒，必须全部丢弃）─────────────────────

    @Test
    fun `支付宝还款提醒必须丢弃`() {
        val samples = listOf(
            "信用贷自动还款失败" to "信用贷今天需还186.14元，系统扣款失败，点我还款",
            "花呗还款失败" to "花呗自动还款失败，最低还款剩余355.92元没还，请登录“支付宝-花呗”核对并还款，已还忽略。",
            "本月花呗账单已还清最低" to "剩余2790.16元未还，请及时查看并核对"
        )
        samples.forEach { (title, body) ->
            assertEquals(
                "「$title」应被排除词拦下",
                DiscardReason.EXCLUDED,
                expectReason(alipay, title, body)
            )
        }
    }

    @Test
    fun `支付宝非交易通知不记账`() {
        assertEquals(
            DiscardReason.NO_SEMANTIC,
            expectReason(alipay, "支付宝", "你的快递已签收，点击查看详情")
        )
    }

    // ── 文档 §6.8.1 基准用例 ────────────────────────────────────────

    @Test
    fun `N1 微信支出`() {
        val c = expectSuccess(wechat, "微信支付", "微信支付：支付成功 ￥25.00")
        assertEquals(Direction.EXPENSE, c.direction)
        assertEquals(2_500L, c.amountCents)
    }

    @Test
    fun `N2 微信收入`() {
        val c = expectSuccess(wechat, "微信支付", "微信支付：你收到一笔转账 ￥200.00")
        assertEquals(Direction.INCOME, c.direction)
        assertEquals(20_000L, c.amountCents)
    }

    @Test
    fun `N3 微信退款记为收入并建议退款分类`() {
        val c = expectSuccess(wechat, "微信支付", "微信支付：已退款 ￥25.00")
        assertEquals(Direction.INCOME, c.direction)
        assertEquals(2_500L, c.amountCents)
        assertEquals("refund", c.suggestedCategoryId)
    }

    @Test
    fun `N4 支付宝支出`() {
        val c = expectSuccess(alipay, "支付宝", "你已成功付款 12.00元")
        assertEquals(Direction.EXPENSE, c.direction)
        assertEquals(1_200L, c.amountCents)
    }

    @Test
    fun `N5 支付宝收入`() {
        val c = expectSuccess(alipay, "支付宝", "你已收到转账 100.00元")
        assertEquals(Direction.INCOME, c.direction)
        assertEquals(10_000L, c.amountCents)
    }

    @Test
    fun `N6 收款码更新不记账`() {
        assertEquals(DiscardReason.EXCLUDED, expectReason(wechat, "微信支付", "微信支付：收款码已更新"))
    }

    @Test
    fun `N7 非支付标题直接丢弃`() {
        assertEquals(DiscardReason.NOT_PAYMENT_TITLE, expectReason(wechat, "张三", "在吗"))
    }

    @Test
    fun `N8 账户安全通知不记账`() {
        assertEquals(DiscardReason.EXCLUDED, expectReason(alipay, "支付宝", "你的账户有一笔登录"))
    }

    @Test
    fun `N9 千分位金额`() {
        val c = expectSuccess(wechat, "微信支付", "微信支付：支付成功 ￥1,280.50")
        assertEquals(128_050L, c.amountCents)
    }

    @Test
    fun `N10 缺金额不生成流水`() {
        assertEquals(DiscardReason.NO_AMOUNT, expectReason(wechat, "微信支付", "你有一笔新的收款"))
    }

    @Test
    fun `未知包名直接丢弃`() {
        assertEquals(
            DiscardReason.NOT_PAYMENT_TITLE,
            expectReason("com.example.other", "微信支付", "支付成功 ￥1.00")
        )
    }

    // ── 金额四级回退 ────────────────────────────────────────────────

    @Test
    fun `金额抽取四级回退`() {
        assertEquals(2_500L, NotificationParser.extractAmountCents("支付成功 ￥25.00"))
        assertEquals(1_200L, NotificationParser.extractAmountCents("你已成功付款12.00元"))
        assertEquals(800L, NotificationParser.extractAmountCents("金额：8.00"))
        assertEquals(3_300L, NotificationParser.extractAmountCents("已支付33.00"))
        assertNull(NotificationParser.extractAmountCents("没有金额"))
    }

    // ── 收款码 / 往来款收入（§6.2.8）────────────────────────────────

    @Test
    fun `收款码到账记为收入但强制待确认`() {
        val c = expectSuccess(wechat, "微信支付", "微信支付：收款到账 ¥50.00")
        assertEquals(Direction.INCOME, c.direction)
        assertEquals(5_000L, c.amountCents)
        assertNull("收款码通知没有付款人信息", c.counterparty)
        assertTrue("无付款方的收入必须强制待确认", c.forcePending)
    }

    @Test
    fun `收到转账标记为往来款`() {
        val c = expectSuccess(wechat, "微信支付", "微信支付：收到转账 ¥300.00")
        assertEquals(Direction.INCOME, c.direction)
        assertEquals("transfer_in", c.suggestedCategoryId)
        assertTrue(c.forcePending)
    }

    @Test
    fun `收款码相关收入不再被误丢弃`() {
        // 曾经的 bug：裸「收款码」在排除词里，会把真实收款静默丢掉
        val c = expectSuccess(wechat, "微信收款助手", "收款到账 ¥20.00")
        assertEquals(Direction.INCOME, c.direction)
        assertEquals(2_000L, c.amountCents)
    }

    @Test
    fun `群收款与赞赏码属于收入不应被排除`() {
        val c = expectSuccess(wechat, "微信支付", "群收款到账 ¥30.00")
        assertEquals(Direction.INCOME, c.direction)
        assertEquals(3_000L, c.amountCents)
    }

    @Test
    fun `收款码配置类通知仍然丢弃`() {
        assertEquals(
            DiscardReason.EXCLUDED,
            expectReason(wechat, "微信支付", "微信支付：收款码已更新")
        )
    }

    @Test
    fun `退款不需要强制待确认`() {
        val c = expectSuccess(wechat, "微信支付", "微信支付：已退款 ￥25.00")
        assertEquals("refund", c.suggestedCategoryId)
        assertTrue("退款性质明确，不应强制待确认", !c.forcePending)
    }

    @Test
    fun `支出从不强制待确认`() {
        val c = expectSuccess(wechat, "微信支付", "微信支付：支付成功 ￥25.00")
        assertTrue(!c.forcePending)
    }

    // ── 真机样本 2026-10-29（抓取助手导出，6273 条通知里筛出的微信支付相关）────

    @Test
    fun `真机 未合并的微信支付通知`() {
        // 实测原文（title=微信支付, text=已支付¥1.74，没有 [N条] 前缀）
        val c = expectSuccess(wechat, "微信支付", "已支付¥1.74")
        assertEquals(Direction.EXPENSE, c.direction)
        assertEquals(174L, c.amountCents)
        assertNull(c.counterparty)
    }

    @Test
    fun `真机 合并后的微信支付通知`() {
        // 实测原文：[5条]微信支付: 已支付¥5.00
        val c = expectSuccess(wechat, "微信支付", "[5条]微信支付: 已支付¥5.00")
        assertEquals(Direction.EXPENSE, c.direction)
        assertEquals(500L, c.amountCents)
    }

    @Test
    fun `真机 退款到账通知没有金额只能丢弃`() {
        // 实测原文：[5条]微信支付: 退款到账通知
        // 微信这条通知**正文里没有金额**（extras 也只有 title/text，没有 bigText/textLines），
        // 通道 A 拿不到退款金额，只能丢弃，退款金额靠 CSV 兜底。
        assertEquals(
            DiscardReason.NO_AMOUNT,
            expectReason(wechat, "微信支付", "[5条]微信支付: 退款到账通知")
        )
    }

    @Test
    fun `真机 零钱提现不该记成收支`() {
        // 实测原文：[3条]微信支付: 零钱提现发起 / [4条]微信支付: 零钱提现已到账
        // 自己的钱从零钱搬到银行卡，既不是收入也不是支出。
        // 如果不排除，「零钱提现已到账」会命中收入词「已到账」被记成一笔假收入。
        assertEquals(
            DiscardReason.EXCLUDED,
            expectReason(wechat, "微信支付", "[3条]微信支付: 零钱提现发起")
        )
        assertEquals(
            DiscardReason.EXCLUDED,
            expectReason(wechat, "微信支付", "[4条]微信支付: 零钱提现已到账")
        )
    }

    @Test
    fun `服务号通知不在 title 白名单`() {
        // 典型原文：title=示例充电服务, text=[3条]示例充电服务: 充电结束通知
        // 小程序的通知没有金额，也不该进管线
        assertEquals(
            DiscardReason.NOT_PAYMENT_TITLE,
            expectReason(wechat, "示例充电服务", "[3条]示例充电服务: 充电结束通知")
        )
    }

    @Test
    fun `真机 支付宝营销推送全部丢弃`() {
        // 支付宝没有 title 白名单（见 §4.3），只能靠排除词 + 语义兜住
        assertEquals(
            DiscardReason.NO_SEMANTIC,
            expectReason(alipay, "网警的安全提示来了！", "点击查看详情>>")
        )
        assertEquals(
            DiscardReason.EXCLUDED,
            expectReason(alipay, "你已具备借款申请资格", "还款可分60期，临时借款来「用钱」>")
        )
        assertEquals(
            DiscardReason.EXCLUDED,
            expectReason(
                alipay,
                "新消息                                                                       GroupSummary",
                "你有一条新消息"
            )
        )
    }
}

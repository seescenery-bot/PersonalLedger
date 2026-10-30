package com.mizhii.ledger.domain.capture

import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.TxnSource
import com.mizhii.ledger.domain.model.DiscardReason
import com.mizhii.ledger.domain.model.ParseOutcome
import com.mizhii.ledger.domain.model.TxnCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通道 B 页面解析测试（需求文档 §6.8.2 的 A1–A5 + 余额保护等边界）。
 * 这是 M3 的可验收部分：真机页面结构校准要等设备，但抽取逻辑现在就能锁死。
 */
class A11yPageParserTest {

    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private val postTime = 1_793_001_600_000L

    private fun parse(pkg: String, texts: List<String>) =
        A11yPageParser.parse(A11yPageInput(pkg, texts, postTime))

    private fun expectSuccess(pkg: String, texts: List<String>): TxnCandidate {
        val o = parse(pkg, texts)
        assertTrue("期望解析成功，实际是 $o", o is ParseOutcome.Success)
        return (o as ParseOutcome.Success).candidate
    }

    private fun expectReason(pkg: String, texts: List<String>): String {
        val o = parse(pkg, texts)
        assertTrue("期望被丢弃，实际是 $o", o is ParseOutcome.Discarded)
        return (o as ParseOutcome.Discarded).reason
    }

    // ── 文档 §6.8.2 A1–A5 ───────────────────────────────────────────

    @Test
    fun `A1 微信支付成功页含商户与单号`() {
        val c = expectSuccess(
            wechat,
            listOf("支付成功", "￥25.00", "美团外卖", "交易单号 4200001234202605011234567890")
        )
        assertEquals(Direction.EXPENSE, c.direction)
        assertEquals(2_500L, c.amountCents)
        assertEquals("美团外卖", c.counterparty)
        assertEquals("4200001234202605011234567890", c.externalNo)
        assertEquals(Channel.WECHAT, c.channel)
        assertEquals(TxnSource.ACCESSIBILITY, c.source)
    }

    @Test
    fun `A2 收款方同行标签`() {
        val c = expectSuccess(wechat, listOf("支付成功", "¥12.00", "收款方 星巴克咖啡"))
        assertEquals(1_200L, c.amountCents)
        assertEquals("星巴克咖啡", c.counterparty)
    }

    @Test
    fun `A3 转账成功页`() {
        val c = expectSuccess(wechat, listOf("转账成功", "￥200.00", "收款方 李四"))
        assertEquals(Direction.EXPENSE, c.direction)
        assertEquals(20_000L, c.amountCents)
        assertEquals("李四", c.counterparty)
    }

    @Test
    fun `A4 只有金额没有商户`() {
        val c = expectSuccess(wechat, listOf("支付成功", "￥8.00"))
        assertEquals(800L, c.amountCents)
        assertNull(c.counterparty)
    }

    @Test
    fun `A5 余额页不是支付页`() {
        assertEquals(
            DiscardReason.NO_SEMANTIC,
            expectReason(alipay, listOf("余额宝", "昨日收益 0.52", "总金额 12,345.67"))
        )
    }

    // ── 余额保护（最容易出错的地方）────────────────────────────────

    @Test
    fun `余额不会被误当成支付金额`() {
        val c = expectSuccess(
            wechat,
            listOf("支付成功", "零钱余额 ¥1,234.56", "￥25.00", "美团外卖")
        )
        assertEquals("应取 25.00 而不是余额 1234.56", 2_500L, c.amountCents)
    }

    @Test
    fun `总收入页`() {
        val c = expectSuccess(alipay, listOf("收款成功", "¥100.00", "张三"))
        assertEquals(Direction.INCOME, c.direction)
        assertEquals(10_000L, c.amountCents)
    }

    @Test
    fun `标签独立成节点时取下一个节点`() {
        val c = expectSuccess(
            wechat,
            listOf("支付成功", "￥66.00", "收款方", "肯德基", "交易单号", "4200009999202605011234")
        )
        assertEquals("肯德基", c.counterparty)
        assertEquals("4200009999202605011234", c.externalNo)
    }

    @Test
    fun `页面时间优先于通知时间`() {
        val c = expectSuccess(
            wechat,
            listOf("支付成功", "￥10.00", "支付时间 2026-10-26 21:39:12")
        )
        assertEquals("2026-10-26", com.mizhii.ledger.util.Dates.dayKey(c.occurredAt))
        assertEquals("21:39", com.mizhii.ledger.util.Dates.timeLabel(c.occurredAt))
    }

    @Test
    fun `没有金额则丢弃`() {
        assertEquals(
            DiscardReason.NO_AMOUNT,
            expectReason(wechat, listOf("支付成功", "请稍候"))
        )
    }

    @Test
    fun `非白名单包名直接丢弃`() {
        assertEquals(
            DiscardReason.NOT_PAYMENT_TITLE,
            expectReason("com.example.other", listOf("支付成功", "￥1.00"))
        )
    }

    @Test
    fun `空文本列表不崩溃`() {
        assertEquals(DiscardReason.NO_SEMANTIC, expectReason(wechat, emptyList()))
    }

    @Test
    fun `商户名不会取到单号或时间`() {
        val c = expectSuccess(
            wechat,
            listOf("支付成功", "￥18.00", "交易单号 4200001234", "支付时间 2026-10-26 10:00:00")
        )
        assertNull("不应把单号或时间当商户", c.counterparty)
    }

    @Test
    fun `解析后的候选可交给置信度评分`() {
        val c = expectSuccess(
            wechat,
            listOf("支付成功", "￥25.00", "美团外卖", "交易单号 4200001234202605011234567890")
        )
        val score = ConfidenceScorer.score(c, hasClearSemantic = true, hasRuleMatch = true)
        // 25 + 25 + 15 + 20 + 15 + 10 = 110，封顶 100
        assertEquals(100, score)
        assertTrue(ConfidenceScorer.isConfirmed(score, 70))
        assertNotNull(c.rawText)
    }
}

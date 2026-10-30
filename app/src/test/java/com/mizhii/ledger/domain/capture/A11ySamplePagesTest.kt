package com.mizhii.ledger.domain.capture

import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.domain.model.DiscardReason
import com.mizhii.ledger.domain.model.ParseOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 通道 B 页面解析回归（合成样本）。
 *
 * fixture `fixtures/pages_sample.txt` 是**合成的 13 个页面**，全部文本为虚构示例，
 * 但节点顺序与真机采集到的页面结构一致（格式见 [A11yPageParser] 类注释）。
 *
 * 这个测试的价值在**双向**：
 *  - 该解析的 3 个支付结果页必须解析出来；
 *  - 剩下 10 个页面一个都不能解析 —— 误报比漏报危险得多（用户只会看到账本里凭空多了一笔）。
 */
class A11ySamplePagesTest {

    private data class Page(
        val file: String,
        val pkg: String,
        val win: String,
        val texts: List<String>,
    )

    private val postTime = 1_793_100_000_000L

    private val pages: List<Page> by lazy {
        val f = File("src/test/resources/fixtures/pages_sample.txt")
        assertTrue("页面 fixture 缺失: ${f.absolutePath}", f.exists())
        f.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split('\u001F')
                Page(
                    file = parts[0],
                    pkg = parts[1],
                    win = parts.getOrElse(2) { "" },
                    texts = if (parts.size > 3) {
                        parts[3].split('\u001E').filter { it.isNotBlank() }
                    } else {
                        emptyList()
                    }
                )
            }
    }

    private fun Page.parse(): ParseOutcome =
        A11yPageParser.parse(A11yPageInput(pkg, texts, postTime))

    private fun pageOf(suffix: String): Page {
        val p = pages.firstOrNull { it.file.endsWith(suffix) }
        assertTrue("fixture 里找不到页面 $suffix", p != null)
        return p!!
    }

    // ── 三个支付结果页 ────────────────────────────────────────────

    @Test
    fun `微信支付成功页`() {
        // 节点顺序：支付成功 / 示例商户A（**例） / ¥5.00 / 完成
        val out = pageOf("com.tencent.mm-003.json").parse()
        assertTrue("应解析成功，实际 $out", out is ParseOutcome.Success)
        val c = (out as ParseOutcome.Success).candidate
        assertEquals(Channel.WECHAT, c.channel)
        assertEquals(Direction.EXPENSE, c.direction)
        assertEquals(500L, c.amountCents)
        assertEquals("示例商户A（**例）", c.counterparty)
        assertEquals("支付成功", c.originTitle)
        assertTrue("支出不该强制待确认", !c.forcePending)
    }

    @Test
    fun `支付宝支付成功页`() {
        // 节点顺序：回首页 / 支付成功 / 4.50 / 示例便利店有限公司 / ￥4.50 / 付款方式
        val out = pageOf("AlipayGphone-004.json").parse()
        assertTrue("应解析成功，实际 $out", out is ParseOutcome.Success)
        val c = (out as ParseOutcome.Success).candidate
        assertEquals(Channel.ALIPAY, c.channel)
        assertEquals(Direction.EXPENSE, c.direction)
        assertEquals(450L, c.amountCents)
        assertEquals("示例便利店有限公司", c.counterparty)
    }

    @Test
    fun `双向证据冲突的支付回执必须待确认`() {
        // 这个页面同时有「收款成功通知」（收款方视角）和「支付金额：20.00元」（付款方视角），
        // 实际是用户付出去 20 元。方向按硬支出证据判 EXPENSE，但必须强制人工裁决。
        val out = pageOf("com.tencent.mm-005.json").parse()
        assertTrue("应解析成功，实际 $out", out is ParseOutcome.Success)
        val c = (out as ParseOutcome.Success).candidate
        assertEquals(Direction.EXPENSE, c.direction)
        assertEquals(2_000L, c.amountCents)
        assertTrue("双向证据冲突必须强制待确认", c.forcePending)
    }

    // ── 零误报（比"能解析"更重要）──────────────────────────────────

    @Test
    fun `13 个页面里只有 3 个是支付结果页`() {
        assertTrue("fixture 应覆盖足够多的页面，实际 ${pages.size}", pages.size >= 12)

        val successes = pages.mapNotNull { p ->
            val out = p.parse()
            if (out is ParseOutcome.Success) p.file to (out as ParseOutcome.Success).candidate else null
        }

        val expected = setOf(
            "1002-com.tencent.mm-003.json",
            "1003-com.eg.android.AlipayGphone-004.json",
            "1004-com.tencent.mm-005.json",
        )
        assertEquals(
            "页面解析结果与基线不一致（新增成功项 = 误报风险，必须人工核对）",
            expected,
            successes.map { it.first }.toSet()
        )
    }

    @Test
    fun `微信首页不会被误判成收入`() {
        // 历史误报：聊天预览「[转账] 已收款」+ 版本号文本「3.4」→ 曾被解析成一笔收入。
        val p = pageOf("com.tencent.mm-001.json")
        assertTrue("页面应包含「已收款」干扰文本", p.texts.any { it.contains("已收款") })
        assertTrue("页面应包含版本号干扰文本", p.texts.any { it.contains("3.4") })

        val out = p.parse()
        assertTrue("微信首页不能被解析成账目，实际 $out", out is ParseOutcome.Discarded)
        assertEquals(DiscardReason.NO_SEMANTIC, (out as ParseOutcome.Discarded).reason)
    }

    @Test
    fun `页面切换中间态不会被误判`() {
        // 「收付款页 + 上一个支付成功页」叠在一起的中间态：支付成功出现在很靠后的文本里，
        // 靠顶部窗口规则排除。
        val p = pageOf("AlipayGphone-006.json")
        assertTrue(p.texts.any { it == "支付成功" })
        val out = p.parse()
        assertTrue("中间态页面不能被解析，实际 $out", out is ParseOutcome.Discarded)
    }

    @Test
    fun `付款确认页不是支付成功页`() {
        // 付款前的确认页：「付款给xxx / ￥5.00 / 付款方式 / 更改 / 零钱 / 支付」
        // 这里绝不能记账，否则用户取消支付就会多一笔假账。
        val out = pageOf("com.tencent.mm-002.json").parse()
        assertTrue("付款确认页必须丢弃，实际 $out", out is ParseOutcome.Discarded)
    }
}

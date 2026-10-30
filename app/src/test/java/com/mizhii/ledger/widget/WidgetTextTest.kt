package com.mizhii.ledger.widget

import com.mizhii.ledger.data.db.entity.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小部件文案与格式化（M6）。
 *
 * 这一层是纯函数，所以能覆盖"桌面上到底显示什么字"——
 * RemoteViews 本身没法单测，把能抽出来的逻辑抽干净是为了让版面之外的错误无处可藏。
 */
class WidgetTextTest {

    private fun snapshot(
        expense: Long = 526_46L,
        income: Long = 3134_19L,
        count: Int = 20,
        recent: List<WidgetRecent> = emptyList(),
    ) = WidgetSnapshot(
        monthKey = "2026-10",
        expenseCents = expense,
        incomeCents = income,
        txnCount = count,
        recent = recent,
    )

    private fun recent(
        counterparty: String? = "示例便利店",
        cents: Long = 1250,
        direction: Direction = Direction.EXPENSE,
        dayKey: String = "2026-10-29",
    ) = WidgetRecent(1, counterparty, cents, direction, dayKey)

    @Test
    fun `月份标题只留几月`() {
        assertEquals("10月", WidgetText.monthLabel("2026-10"))
        assertEquals("1月", WidgetText.monthLabel("2026-01"))
        assertEquals("12月", WidgetText.monthLabel("2025-12"))
    }

    @Test
    fun `日期按规范用月-日`() {
        // HyperOS 规范 §4.2.3：日期统一「年–月–日 / 月–日」
        assertEquals("10–29", WidgetText.dayLabel("2026-10-29"))
        assertEquals("1–5", WidgetText.dayLabel("2026-01-05"))
        // 脏数据不能崩
        assertEquals("不是日期", WidgetText.dayLabel("不是日期"))
    }

    @Test
    fun `汇总数字带货币符号且不丢分`() {
        val s = snapshot()
        assertEquals("¥526.46", WidgetText.expenseText(s))
        assertEquals("¥3,134.19", WidgetText.incomeText(s))
    }

    @Test
    fun `结余是收入减支出且可为负`() {
        assertEquals(3134_19L - 526_46L, snapshot().balanceCents)
        assertTrue(WidgetText.balanceText(snapshot()).startsWith("¥"))

        val negative = snapshot(expense = 10_000L, income = 2_000L)
        assertEquals(-8_000L, negative.balanceCents)
        assertTrue("负结余要能显示出来", WidgetText.balanceText(negative).contains("-"))
    }

    @Test
    fun `零金额显示为零而不是空`() {
        val zero = snapshot(expense = 0L, income = 0L)
        assertEquals("¥0.00", WidgetText.expenseText(zero))
        assertEquals("¥0.00", WidgetText.balanceText(zero))
    }

    @Test
    fun `最近流水的正负号跟随方向`() {
        assertEquals("-¥12.50", WidgetText.signedAmount(recent()))
        assertEquals("+¥12.50", WidgetText.signedAmount(recent(direction = Direction.INCOME)))
    }

    @Test
    fun `没有商户名时退化成占位文案而不是空白`() {
        assertEquals("未填写对方", WidgetText.recentTitle(recent(counterparty = null)))
        assertEquals("未填写对方", WidgetText.recentTitle(recent(counterparty = "   ")))
        assertEquals("示例便利店", WidgetText.recentTitle(recent()))
    }

    @Test
    fun `没有数据时判定为空并给出引导文案`() {
        assertFalse(snapshot(count = 0).hasData)
        assertTrue(snapshot(count = 1).hasData)
        assertTrue(WidgetText.emptyTitle().isNotBlank())
        assertTrue(WidgetText.emptyHint().isNotBlank())
    }

    @Test
    fun `底部统计行带上笔数`() {
        assertEquals("本月 20 笔", WidgetText.footer(snapshot(count = 20)))
        assertEquals("本月 0 笔", WidgetText.footer(snapshot(count = 0)))
    }

    @Test
    fun `尺寸不同给的最近条数不同`() {
        // 尺寸变大加信息密度：4×2 放 3 条，4×4 放 5 条
        assertEquals(3, WidgetText.MAX_RECENT_WIDE)
        assertEquals(5, WidgetText.MAX_RECENT_TALL)
        assertTrue(WidgetText.MAX_RECENT_TALL > WidgetText.MAX_RECENT_WIDE)
    }
}

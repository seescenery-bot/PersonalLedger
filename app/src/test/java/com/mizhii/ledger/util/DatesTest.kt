package com.mizhii.ledger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DatesTest {

    @Test
    fun `dayKey 与 monthKey 使用本地时区`() {
        val millis = Dates.toEpochMillis(LocalDate.of(2026, 5, 8), 14, 32)
        assertEquals("2026-05-08", Dates.dayKey(millis))
        assertEquals("2026-05", Dates.monthKey(millis))
    }

    @Test
    fun `月份首末日期`() {
        assertEquals("2026-05-01", Dates.firstDayOfMonth("2026-05"))
        assertEquals("2026-05-31", Dates.lastDayOfMonth("2026-05"))
        assertEquals("2026-02-28", Dates.lastDayOfMonth("2026-02"))
        assertEquals("2024-02-29", Dates.lastDayOfMonth("2024-02")) // 闰年
    }

    @Test
    fun `月份前后推算跨年`() {
        assertEquals("2025-12", Dates.previousMonth("2026-01", -1))
        assertEquals("2027-01", Dates.previousMonth("2026-12", 1))
        assertEquals("2025-10", Dates.previousMonth("2026-05", -7))
    }

    @Test
    fun `日期分组标题`() {
        // 2026-05-08 是周五
        assertEquals("5月8日 周五", Dates.dayHeaderLabel("2026-05-08"))
        // 2026-01-01 是周四
        assertEquals("1月1日 周四", Dates.dayHeaderLabel("2026-01-01"))
    }

    @Test
    fun `年份月份提取`() {
        assertEquals(2026, Dates.yearOf("2026-05"))
        assertEquals(5, Dates.monthOf("2026-05"))
    }

    @Test
    fun `isToday 判定`() {
        assertTrue(Dates.isToday(Dates.dayKey(System.currentTimeMillis())))
        assertFalse(Dates.isToday("2000-01-01"))
    }

    @Test
    fun `withDate 保留时分`() {
        val original = Dates.toEpochMillis(LocalDate.of(2026, 5, 8), 14, 32)
        val moved = Dates.withDate(original, LocalDate.of(2026, 6, 1))
        assertEquals("2026-06-01", Dates.dayKey(moved))
        assertEquals("14:32", Dates.timeLabel(moved))
    }

    @Test
    fun `时间展示`() {
        val millis = Dates.toEpochMillis(LocalDate.of(2026, 5, 8), 9, 5)
        assertEquals("09:05", Dates.timeLabel(millis))
        assertEquals("2026-05-08 09:05", Dates.dateTimeLabel(millis))
    }
}

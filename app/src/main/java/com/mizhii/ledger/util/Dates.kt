package com.mizhii.ledger.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时间工具（文档 §8.9）。
 * 统一使用 java.time，禁止 Calendar/Date。月份归属一律以本地时区为准。
 */
object Dates {

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** epoch millis → "2026-05-08"（本地时区） */
    fun dayKey(instantMillis: Long): String = toLocalDate(instantMillis).toString()

    fun toLocalDate(instantMillis: Long): LocalDate =
        Instant.ofEpochMilli(instantMillis).atZone(zone).toLocalDate()

    fun toLocalDateTime(instantMillis: Long): LocalDateTime =
        Instant.ofEpochMilli(instantMillis).atZone(zone).toLocalDateTime()

    /** epoch millis → "2026-05" */
    fun monthKey(instantMillis: Long): String = YearMonth.from(toLocalDate(instantMillis)).toString()

    fun monthKeyOf(date: LocalDate): String = YearMonth.from(date).toString()

    fun today(): LocalDate = LocalDate.now(zone)

    fun currentMonthKey(): String = monthKeyOf(today())

    /** "2026-05" → "2026-05-01" */
    fun firstDayOfMonth(monthKey: String): String = "$monthKey-01"

    /** "2026-05" → "2026-05-31" */
    fun lastDayOfMonth(monthKey: String): String {
        val length = YearMonth.parse(monthKey).lengthOfMonth()
        return "$monthKey-${length.toString().padStart(2, '0')}"
    }

    fun previousMonth(monthKey: String, delta: Int): String =
        YearMonth.parse(monthKey).plusMonths(delta.toLong()).toString()

    fun yearOf(monthKey: String): Int = YearMonth.parse(monthKey).year

    fun monthOf(monthKey: String): Int = YearMonth.parse(monthKey).monthValue

    private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** "2026-05-08" → "5月8日 周五" */
    fun dayHeaderLabel(dayKey: String): String {
        val date = LocalDate.parse(dayKey)
        val weekday = WEEKDAYS[date.dayOfWeek.value - 1]
        return "${date.monthValue}月${date.dayOfMonth}日 $weekday"
    }

    fun isToday(dayKey: String): Boolean = LocalDate.parse(dayKey) == today()

    /** epoch millis → "14:32" */
    fun timeLabel(instantMillis: Long): String =
        toLocalDateTime(instantMillis).format(TIME_FORMATTER)

    /** epoch millis → "2026-05-08 14:32" */
    fun dateTimeLabel(instantMillis: Long): String =
        "${dayKey(instantMillis)} ${timeLabel(instantMillis)}"

    fun toEpochMillis(date: LocalDate, hour: Int = 0, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** 保留原有"时分"，换一个日期 */
    fun withDate(instantMillis: Long, newDate: LocalDate): Long {
        val old = toLocalDateTime(instantMillis)
        return newDate.atTime(old.hour, old.minute).atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * Material3 DatePicker 的 selectedDateMillis 是 **UTC 零点**，
     * 直接按本地时区解析会差一天，必须用 UTC 解析、UTC 回写。
     */
    fun localDateFromPickerMillis(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    fun toPickerMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

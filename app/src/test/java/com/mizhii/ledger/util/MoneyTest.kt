package com.mizhii.ledger.util

import com.mizhii.ledger.data.db.entity.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 金额工具单测（文档 §12 M1 验收：金额格式化全部正确）。
 * 金额是最容易出错的地方，这里覆盖格式化、解析与边界。
 */
class MoneyTest {

    @Test
    fun `format 保留两位小数`() {
        assertEquals("0.00", Money.format(0))
        assertEquals("0.05", Money.format(5))
        assertEquals("0.50", Money.format(50))
        assertEquals("1.00", Money.format(100))
        assertEquals("25.00", Money.format(2_500))
    }

    @Test
    fun `format 带千分位`() {
        assertEquals("1,280.50", Money.format(128_050))
        assertEquals("1,000.00", Money.format(100_000))
        assertEquals("1,234,567.89", Money.format(123_456_789))
    }

    @Test
    fun `format 负数`() {
        assertEquals("-25.00", Money.format(-2_500))
    }

    @Test
    fun `formatWithSymbol 与 formatSigned`() {
        assertEquals("¥25.00", Money.formatWithSymbol(2_500))
        assertEquals("-¥25.00", Money.formatSigned(2_500, Direction.EXPENSE))
        assertEquals("+¥200.00", Money.formatSigned(20_000, Direction.INCOME))
    }

    @Test
    fun `parseYuanToCents 基本解析`() {
        assertEquals(2_500L, Money.parseYuanToCents("25"))
        assertEquals(2_550L, Money.parseYuanToCents("25.5"))
        assertEquals(2_500L, Money.parseYuanToCents("25.00"))
        assertEquals(5L, Money.parseYuanToCents("0.05"))
        assertEquals(120_000L, Money.parseYuanToCents("1200"))
    }

    @Test
    fun `parseYuanToCents 容忍千分位与货币符号`() {
        assertEquals(128_050L, Money.parseYuanToCents("1,280.50"))
        assertEquals(1_200L, Money.parseYuanToCents("¥12.00"))
        assertEquals(1_200L, Money.parseYuanToCents("￥12.00"))
        assertEquals(1_200L, Money.parseYuanToCents(" 12.00 "))
    }

    @Test
    fun `parseYuanToCents 容忍未输入完的小数点`() {
        assertEquals(1_200L, Money.parseYuanToCents("12."))
    }

    @Test
    fun `parseYuanToCents 拒绝非法输入`() {
        assertNull(Money.parseYuanToCents(""))
        assertNull(Money.parseYuanToCents("   "))
        assertNull(Money.parseYuanToCents("abc"))
        assertNull(Money.parseYuanToCents("1.234"))       // 超过两位小数
        assertNull(Money.parseYuanToCents("1.2.3"))
        assertNull(Money.parseYuanToCents("-5"))          // 负数：方向由 direction 表达
        assertNull(Money.parseYuanToCents("1234567890"))  // 整数位超过 9 位
    }

    @Test
    fun `toInputText 不含千分位`() {
        assertEquals("1280.50", Money.toInputText(128_050))
        assertEquals("25", Money.toInputText(2_500))
        assertEquals("0.05", Money.toInputText(5))
    }

    @Test
    fun `解析与格式化互为逆运算`() {
        val samples = longArrayOf(0, 1, 5, 99, 100, 2_500, 128_050, 123_456_789)
        for (cents in samples) {
            val text = Money.format(cents)
            assertEquals("roundtrip failed for $cents", cents, Money.parseYuanToCents(text))
        }
    }

    @Test
    fun `部分输入校验`() {
        assertEquals(true, Money.isPartialInputValid(""))
        assertEquals(true, Money.isPartialInputValid("0."))
        assertEquals(true, Money.isPartialInputValid("12.3"))
        assertEquals(false, Money.isPartialInputValid("12.345"))
        assertEquals(false, Money.isPartialInputValid("12a"))
    }
}

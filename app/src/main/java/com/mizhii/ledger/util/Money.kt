package com.mizhii.ledger.util

import com.mizhii.ledger.data.db.entity.Direction
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 金额工具（文档 §8.9）。
 *
 * 铁律：金额在本项目里**只用 Long 表示"分"**，任何地方都不得用 Double/Float 参与金额运算。
 * 本对象是全局唯一允许出现"元"这个概念的地方。
 */
object Money {

    const val SYMBOL = "¥"

    /** 分 → 带千分位的元：2500 -> "25.00"，128050 -> "1,280.50" */
    fun format(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val body = "${groupThousands(abs / 100)}.${(abs % 100).toString().padStart(2, '0')}"
        return if (negative) "-$body" else body
    }

    /** 分 → "¥25.00"；负数时符号在货币符号之前，得到 "-¥25.00" 而不是 "¥-25.00" */
    fun formatWithSymbol(cents: Long): String {
        val abs = if (cents < 0) -cents else cents
        val sign = if (cents < 0) "-" else ""
        return "$sign$SYMBOL${format(abs)}"
    }

    /** 分 + 方向 → "-¥25.00" / "+¥25.00" */
    fun formatSigned(cents: Long, direction: Direction): String {
        val sign = if (direction == Direction.EXPENSE) "-" else "+"
        return "$sign$SYMBOL${format(cents)}"
    }

    /** 分 → 不带千分位的元，用于输入框回显（128050 -> "1280.50"） */
    fun toInputText(cents: Long): String {
        val yuan = cents / 100
        val fen = (cents % 100).toInt()
        return if (fen == 0) yuan.toString() else "$yuan.${fen.toString().padStart(2, '0')}"
    }

    // 整数部分最多 9 位（上限约 10 亿元），小数最多 2 位
    private val INPUT_PATTERN = Regex("^\\d{1,9}(\\.\\d{0,2})?$")

    /**
     * 元字符串 → 分。非法输入、负数、超过 2 位小数一律返回 null。
     * 用 BigDecimal + movePointRight(2) + longValueExact，全程精确。
     */
    fun parseYuanToCents(input: String): Long? {
        var cleaned = input.trim()
            .replace(",", "")
            .replace(SYMBOL, "")
            .replace("￥", "")
            .replace(" ", "")
        if (cleaned.isEmpty()) return null
        if (!INPUT_PATTERN.matches(cleaned)) return null
        // "12." 这种尾逗号形式 BigDecimal 不接受，先归一化
        if (cleaned.endsWith(".")) cleaned = cleaned.dropLast(1)
        return try {
            BigDecimal(cleaned)
                .movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
        } catch (e: ArithmeticException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** 是否是合法的"进行中"输入（允许 "0."、"12." 这种中间态，供键盘校验用） */
    fun isPartialInputValid(input: String): Boolean {
        if (input.isEmpty()) return true
        if (!input.matches(Regex("^\\d{0,9}(\\.\\d{0,2})?$"))) return false
        return true
    }

    private fun groupThousands(value: Long): String {
        val s = value.toString()
        if (s.length <= 3) return s
        val sb = StringBuilder()
        var count = 0
        for (i in s.indices.reversed()) {
            sb.append(s[i])
            count++
            if (count % 3 == 0 && i != 0) sb.append(',')
        }
        return sb.reverse().toString()
    }
}

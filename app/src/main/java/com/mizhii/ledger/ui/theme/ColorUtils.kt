package com.mizhii.ledger.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt

/**
 * 把数据库里存的 "#RRGGBB" 转成 Compose Color。
 * 用户可自定义颜色 / 数据可能损坏，解析失败必须回落而不是崩溃。
 */
fun colorFromHex(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return try {
        Color(hex.toColorInt())
    } catch (e: IllegalArgumentException) {
        fallback
    }
}

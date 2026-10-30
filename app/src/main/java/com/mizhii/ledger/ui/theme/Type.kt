package com.mizhii.ledger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Base = Typography()

val LedgerTypography = Base.copy(
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Medium)
)

/**
 * 金额展示样式（§10.2）。
 * 必须启用 tabular figures（tnum），否则不同数字宽度会导致金额纵向错位。
 */
val AmountLarge = TextStyle(
    fontSize = 32.sp,
    fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = "tnum"
)

val AmountNormal = TextStyle(
    fontSize = 16.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum"
)

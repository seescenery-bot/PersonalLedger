package com.mizhii.ledger.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mizhii.ledger.R
import com.mizhii.ledger.ui.common.EmptyState
import com.mizhii.ledger.ui.home.MonthPickerDialog
import com.mizhii.ledger.ui.theme.AmountLarge
import com.mizhii.ledger.ui.theme.AmountNormal
import com.mizhii.ledger.ui.theme.LocalLedgerExtraColors
import com.mizhii.ledger.ui.theme.colorFromHex
import com.mizhii.ledger.util.Dates
import com.mizhii.ledger.util.Money
import kotlin.math.min

/**
 * 月度统计（需求文档 §7.5）。
 * 图表全部用 Compose Canvas 手绘，不引第三方图表库（§9.3 依赖精简）。
 */
@Composable
fun StatsScreen(
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showMonthPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        MonthHeader(
            monthKey = state.monthKey,
            onPrev = { viewModel.changeMonth(-1) },
            onNext = { viewModel.changeMonth(1) },
            onClickMonth = { showMonthPicker = true }
        )

        if (!state.hasData) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    title = stringResource(R.string.stats_empty_title),
                    description = stringResource(R.string.stats_empty_desc)
                )
            }
        } else {
            OverviewCard(state)
            Spacer(Modifier.height(12.dp))
            CategorySection(state)
            Spacer(Modifier.height(12.dp))
            DailySection(state)
            Spacer(Modifier.height(12.dp))
            ChannelSection(state)
            Spacer(Modifier.height(12.dp))
            MerchantSection(state)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showMonthPicker) {
        MonthPickerDialog(
            initialMonthKey = state.monthKey,
            onDismiss = { showMonthPicker = false },
            onSelected = {
                viewModel.setMonth(it)
                showMonthPicker = false
            }
        )
    }
}

// ── 顶部月份切换 ──────────────────────────────────────────────────────

@Composable
private fun MonthHeader(
    monthKey: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClickMonth: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onPrev) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null)
        }
        TextButton(onClick = onClickMonth, modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.home_month_format,
                    Dates.yearOf(monthKey),
                    Dates.monthOf(monthKey)
                ),
                style = MaterialTheme.typography.titleLarge
            )
        }
        TextButton(onClick = onNext) {
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

// ── 概览卡 ────────────────────────────────────────────────────────────

@Composable
private fun OverviewCard(state: StatsUiState) {
    val extra = LocalLedgerExtraColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.home_expense),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = Money.formatWithSymbol(state.expenseCents),
                style = AmountLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            state.monthOverMonthPercent?.let { pct ->
                val up = pct >= 0
                Text(
                    text = if (up) {
                        "较上月 +" + "%.1f".format(pct) + "%"
                    } else {
                        "较上月 " + "%.1f".format(pct) + "%"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (up) extra.warning else extra.income
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_income),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = Money.formatWithSymbol(state.incomeCents),
                        style = AmountNormal,
                        color = extra.income
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_balance),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = Money.formatWithSymbol(state.balanceCents),
                        style = AmountNormal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ── 分类占比 ──────────────────────────────────────────────────────────

@Composable
private fun CategorySection(state: StatsUiState) {
    SectionCard(title = "分类占比") {
        Row(modifier = Modifier.fillMaxWidth()) {
            DonutChart(
                slices = state.slices,
                totalCents = state.expenseCents,
                modifier = Modifier.size(160.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                state.slices.take(6).forEach { slice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(
                                    color = colorFromHex(slice.colorHex, Color.Gray),
                                    shape = CircleShape
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = slice.name,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "%.1f%%".format(slice.percent * 100f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        state.slices.forEach { slice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(color = colorFromHex(slice.colorHex, Color.Gray), shape = CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = slice.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = Money.formatWithSymbol(slice.totalCents),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "%.1f%%".format(slice.percent * 100f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(52.dp)
                )
            }
        }
    }
}

@Composable
private fun DonutChart(slices: List<CategorySlice>, totalCents: Long, modifier: Modifier = Modifier) {
    val strokeWidth = 26.dp
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = strokeWidth.toPx()
            val diameter = min(size.width, size.height) - strokePx
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            if (slices.isEmpty()) {
                drawArc(
                    color = Color(0xFFE0E3E1),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx)
                )
            } else {
                var start = -90f
                slices.forEach { slice ->
                    val sweep = slice.percent * 360f
                    drawArc(
                        color = colorFromHex(slice.colorHex, Color.Gray),
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Butt)
                    )
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.home_expense),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = Money.formatWithSymbol(totalCents),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

// ── 每日趋势 ──────────────────────────────────────────────────────────

@Composable
private fun DailySection(state: StatsUiState) {
    SectionCard(title = "每日支出") {
        val byDay = remember(state.daily) { state.daily.associate { it.dayKey to it.expenseCents } }
        val maxValue = remember(state.daily) { state.daily.maxOfOrNull { it.expenseCents } ?: 0L }
        val barColor = MaterialTheme.colorScheme.primary

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val days = state.daysInMonth.coerceAtLeast(1)
            val slot = size.width / days
            val gap = slot * 0.18f
            for (day in 1..days) {
                val key = state.monthKey + "-" + day.toString().padStart(2, '0')
                val value = byDay[key] ?: 0L
                val h = if (maxValue > 0) (value.toFloat() / maxValue) * (size.height - 6f) else 0f
                if (h > 0f) {
                    drawRect(
                        color = barColor,
                        topLeft = Offset((day - 1) * slot + gap, size.height - h),
                        size = Size(slot - gap * 2f, h)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "1日",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${state.daysInMonth}日",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── 渠道分布 ──────────────────────────────────────────────────────────

@Composable
private fun ChannelSection(state: StatsUiState) {
    if (state.channels.isEmpty()) return
    val total = state.channels.sumOf { it.totalCents }.coerceAtLeast(1L)
    SectionCard(title = "渠道分布") {
        state.channels.forEach { item ->
            val percent = item.totalCents.toFloat() / total
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = channelName(item.channel),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(64.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .background(
                            // 轨道必须用 surfaceVariant：用 surface 会和卡片同色，看起来像悬空的药丸
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(5.dp)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(percent.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(5.dp)
                            )
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = Money.formatWithSymbol(item.totalCents),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun channelName(raw: String): String = when (raw) {
    "WECHAT" -> stringResource(R.string.channel_wechat)
    "ALIPAY" -> stringResource(R.string.channel_alipay)
    "CASH" -> stringResource(R.string.channel_cash)
    "BANK" -> stringResource(R.string.channel_bank)
    else -> stringResource(R.string.channel_other)
}

// ── 高频商户 ──────────────────────────────────────────────────────────

@Composable
private fun MerchantSection(state: StatsUiState) {
    if (state.merchants.isEmpty()) return
    SectionCard(title = "高频商户") {
        state.merchants.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(20.dp)
                )
                Text(
                    text = item.counterparty,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${item.count} 笔",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = Money.formatWithSymbol(item.totalCents),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ── 通用卡片 ──────────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

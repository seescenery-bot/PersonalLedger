package com.mizhii.ledger.ui.common

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mizhii.ledger.util.PermissionChecks
import com.mizhii.ledger.util.PermissionHealth
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 权限健康卡片（需求文档 §7.7）。
 *
 * 直接把「两个权限的真实状态」摆在设置页最上面 —— 自动记账一旦缺权限就是**完全静默**的，
 * 用户唯一的线索就是这个卡片。2026-10-30 的事故（通知使用权只给了采集工具、没给本应用）
 * 就是靠它才能被一眼发现。
 *
 * 每 1.5 秒重查一次：用户去系统设置里改完权限切回来，这里要能立刻反映出来。
 */
@Composable
fun PermissionHealthCard(
    lastNotifConnectedAt: Long,
    lastA11yConnectedAt: Long,
    onOpenNotifSettings: () -> Unit,
    onOpenA11ySettings: () -> Unit,
    onOpenAppNotificationSettings: () -> Unit,
    onOpenAppDetails: () -> Unit,
) {
    val context = LocalContext.current

    // 通知权限只在用户主动点「去开启」时申请 —— 小米《开发者生态政策》pId=2156/1793 要求
    // 「运行时按需申请，不得提前申请；拒绝后 48 小时内不得再次索要」。
    // 冷启动直接弹窗（旧实现）两条都踩。
    // 若用户已经永久拒绝，系统不会再弹窗、回调立刻返回 false，此时退到系统设置页。
    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) onOpenAppNotificationSettings()
    }

    var health by remember {
        mutableStateOf(PermissionChecks.check(context, lastNotifConnectedAt, lastA11yConnectedAt))
    }

    LaunchedEffect(lastNotifConnectedAt, lastA11yConnectedAt) {
        while (true) {
            health = PermissionChecks.check(context, lastNotifConnectedAt, lastA11yConnectedAt)
            delay(1_500)
        }
    }

    val ok = health.allGood
    // 只有通道 A 时仍然能记账，只是拿不到商户名 —— 别把它说成"完全不能用"，
    // 否则用户会以为账没记上（这跟 2026-10-30 那次"看起来没记上"的事故是同一类误导）。
    val degraded = health.notifListenerEnabled && !health.a11yEnabled
    val title = when {
        ok -> "自动记账已就绪"
        degraded -> "自动记账可用，但缺无障碍补全"
        else -> "⚠️ 自动记账当前不会工作"
    }
    val containerColor = when {
        ok -> MaterialTheme.colorScheme.surfaceVariant
        degraded -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when {
        ok -> MaterialTheme.colorScheme.onSurface
        degraded -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )

            if (!ok) {
                Spacer(Modifier.height(4.dp))
                health.problems.forEach {
                    Text(
                        "· $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            PermissionRow(
                title = "通知使用权",
                enabled = health.notifListenerEnabled,
                subtitle = "最近连接：${formatTime(health.lastNotifConnectedAt)}",
                onOpen = onOpenNotifSettings
            )
            Spacer(Modifier.height(8.dp))
            PermissionRow(
                title = "无障碍服务",
                enabled = health.a11yEnabled,
                subtitle = "最近连接：${formatTime(health.lastA11yConnectedAt)}",
                onOpen = onOpenA11ySettings
            )
            Spacer(Modifier.height(8.dp))
            PermissionRow(
                title = "系统通知权限",
                enabled = health.notificationsEnabled,
                subtitle = "记账提醒需要它（记账本身不受影响）",
                onOpen = {
                    if (health.notificationsEnabled) {
                        onOpenAppNotificationSettings()
                    } else {
                        requestNotificationPermission
                            .launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )

            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(
                "后台保活",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            PermissionRow(
                title = "后台运行限制",
                enabled = !health.backgroundRestricted,
                subtitle = if (health.backgroundRestricted) {
                    "系统限制了后台运行，自动记账会时断时续"
                } else {
                    "系统未限制（isBackgroundRestricted = false）"
                },
                onOpen = onOpenAppDetails
            )
            Spacer(Modifier.height(8.dp))
            PermissionRow(
                title = "省电策略",
                enabled = health.batteryUnrestricted,
                subtitle = if (health.batteryUnrestricted) {
                    "无限制 —— 已在 Doze 白名单内"
                } else {
                    "受系统省电调度，建议在应用信息里设为「无限制」"
                },
                onOpen = onOpenAppDetails
            )

            if (ok && health.lastNotifConnectedAt == 0L && health.lastA11yConnectedAt == 0L) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "权限已开启，但服务还没连接过 —— 开关一次或重启应用即可",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    enabled: Boolean,
    subtitle: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (enabled) "  已开启" else "  未开启",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onOpen) {
            Text(if (enabled) "查看" else "去开启", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.US)

private fun formatTime(ts: Long): String = when {
    ts <= 0L -> "从未"
    else -> TIME_FORMAT.format(Date(ts))
}

/** 供其他界面复用的健康判断 */
fun isAutomationHealthy(health: PermissionHealth): Boolean = health.allGood

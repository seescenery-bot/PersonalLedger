package com.mizhii.ledger.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mizhii.ledger.R
import com.mizhii.ledger.ui.common.PermissionHealthCard
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onOpenCategories: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenParserLab: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenImport: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
            )
        }

        // ── 自动记账 ──
        item { GroupHeader(stringResource(R.string.settings_group_auto)) }
        item {
            // 权限健康检查放最上面：缺权限时自动记账是**完全静默**的，这是唯一可见的线索
            PermissionHealthCard(
                lastNotifConnectedAt = settings.lastNotifConnectedAt,
                lastA11yConnectedAt = settings.lastA11yConnectedAt,
                onOpenNotifSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                onOpenA11ySettings = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                onOpenAppNotificationSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                onOpenAppDetails = {
                    // 澎湃OS 的「省电策略 / 后台限制」都在应用信息页里
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            )
        }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_notify_on_capture),
                subtitle = stringResource(R.string.settings_notify_on_capture_desc),
                checked = settings.notifyOnCapture,
                onCheckedChange = viewModel::setNotifyOnCapture
            )
        }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_notif_enabled),
                subtitle = stringResource(R.string.settings_notif_enabled_desc),
                checked = settings.notifChannelEnabled,
                onCheckedChange = viewModel::setNotifEnabled
            )
        }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_a11y_enabled),
                subtitle = stringResource(R.string.settings_a11y_enabled_desc),
                checked = settings.a11yChannelEnabled,
                onCheckedChange = viewModel::setA11yEnabled
            )
        }
        item {
            SliderRow(
                title = stringResource(R.string.settings_threshold),
                descRes = R.string.settings_threshold_desc,
                value = settings.autoConfirmThreshold,
                range = 40..95,
                steps = 10,
                onCommit = viewModel::setThreshold
            )
        }
        item {
            SliderRow(
                title = stringResource(R.string.settings_dedupe),
                descRes = R.string.settings_dedupe_desc,
                value = settings.dedupeWindowSeconds,
                range = 10..300,
                steps = 28,
                onCommit = viewModel::setDedupeWindow
            )
        }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_debug_any),
                subtitle = stringResource(R.string.settings_debug_any_desc),
                checked = settings.debugAcceptAllPackages,
                onCheckedChange = viewModel::setDebugAcceptAll
            )
        }

        item { HorizontalDivider() }

        // ── 流水与分类 ──
        item { GroupHeader(stringResource(R.string.settings_group_data)) }
        item {
            NavRow(
                title = stringResource(R.string.settings_pending),
                subtitle = stringResource(R.string.settings_pending_desc, pendingCount),
                onClick = onOpenPending
            )
        }
        item {
            NavRow(
                title = stringResource(R.string.settings_categories),
                subtitle = stringResource(R.string.settings_categories_desc),
                onClick = onOpenCategories
            )
        }
        item {
            NavRow(
                title = stringResource(R.string.settings_rules),
                subtitle = stringResource(R.string.settings_rules_desc),
                onClick = onOpenRules
            )
        }
        item {
            NavRow(
                title = stringResource(R.string.settings_import),
                subtitle = stringResource(R.string.settings_import_desc),
                onClick = onOpenImport
            )
        }

        item { HorizontalDivider() }

        // ── 诊断 ──
        item { GroupHeader(stringResource(R.string.settings_group_diag)) }
        item {
            NavRow(
                title = stringResource(R.string.settings_parser_lab),
                subtitle = stringResource(R.string.settings_parser_lab_desc),
                onClick = onOpenParserLab
            )
        }
        item {
            NavRow(
                title = stringResource(R.string.settings_diagnostics),
                subtitle = stringResource(R.string.settings_diagnostics_desc),
                onClick = onOpenDiagnostics
            )
        }

        item { HorizontalDivider() }

        // ── 后续里程碑 ──
        item { GroupHeader(stringResource(R.string.settings_placeholder)) }
        item { DisabledRow(stringResource(R.string.settings_todo_backup)) }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    @StringRes descRes: Int,
    value: Int,
    range: IntRange,
    steps: Int,
    onCommit: (Int) -> Unit
) {
    var local by remember(value) { mutableIntStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(descRes, local),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = local.toFloat(),
            onValueChange = { local = it.roundToInt() },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps
        )
    }
}

@Composable
private fun DisabledRow(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

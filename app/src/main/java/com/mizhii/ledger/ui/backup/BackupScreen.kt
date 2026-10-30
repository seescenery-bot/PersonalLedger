package com.mizhii.ledger.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mizhii.ledger.domain.backup.RestoreMode
import com.mizhii.ledger.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份与恢复（M5 §7.6）。
 *
 * 交互设计的两条底线：
 *  1. **导出必须先设口令**，密码框有"再输一次"，避免手滑打错导致备份永远打不开；
 *  2. **恢复必须先看清内容再确认** —— 解密后展示备份时间与条数，
 *     并且「合并」和「整体替换」是两个明确不同的按钮，不做默认值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val vm: BackupViewModel = viewModel(factory = BackupViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
    var showExportPassword by remember { mutableStateOf(false) }
    var showRestorePassword by remember { mutableStateOf(false) }

    // 导出：先设口令 → 再选保存位置
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val pwd = pendingPassword
        pendingPassword = null
        if (uri != null && pwd != null) vm.export(pwd, uri)
    }

    // 恢复：先选文件 → 再输口令
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "备份文件"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取这个文件")
                    name to bytes
                }
            }
            loaded.onSuccess { (name, bytes) -> vm.onFilePicked(name, bytes) }
                .onFailure { vm.onFilePicked("备份文件", ByteArray(0)) }
        }
    }

    if (showExportPassword) {
        PasswordDialog(
            title = "给备份设置口令",
            subtitle = "备份文件用 AES-256 加密。口令只在你脑子里，丢了就打不开，任何人也找不回。",
            requireConfirm = true,
            onDismiss = { showExportPassword = false },
            onConfirm = { pwd ->
                showExportPassword = false
                pendingPassword = pwd
                createDocument.launch(defaultBackupFileName())
            }
        )
    }

    if (showRestorePassword) {
        PasswordDialog(
            title = "输入备份口令",
            subtitle = state.fileName?.let { "文件：$it" } ?: "",
            requireConfirm = false,
            onDismiss = { showRestorePassword = false },
            onConfirm = { pwd ->
                showRestorePassword = false
                vm.unlock(pwd)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            WarningCard()

            Spacer(Modifier.height(16.dp))

            ExportCard(
                busy = state.busy,
                exportSummaryText = state.exportSummary?.let {
                    "上次导出：${it.txnCount} 笔流水 · ${it.createdLabel}"
                },
                onExport = { showExportPassword = true }
            )

            Spacer(Modifier.height(12.dp))

            RestoreCard(
                busy = state.busy,
                onPickFile = {
                    vm.reset()
                    openDocument.launch(arrayOf("application/octet-stream", "*/*"))
                }
            )

            // 文件已选好，等口令
            if (state.step == BackupViewModel.Step.NEED_PASSWORD) {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "已选择文件",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            state.fileName ?: "备份文件",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { showRestorePassword = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy
                        ) { Text("输入口令并查看内容") }
                    }
                }
            }

            // 解密成功，等确认
            state.pendingRestore?.let { preview ->
                Spacer(Modifier.height(12.dp))
                PreviewCard(
                    summaryText = "这份备份创建于 ${preview.createdLabel}",
                    detailText = "流水 ${preview.txnCount} 笔 · 分类 ${preview.categoryCount} 个 · " +
                        "规则 ${preview.ruleCount} 条 · 忽略 ${preview.ignoreCount} 项 · " +
                        "导入批次 ${preview.batchCount} 个" + "\nApp 版本 ${preview.appVersion}",
                    busy = state.busy,
                    onMerge = { vm.commitRestore(RestoreMode.MERGE) },
                    onReplace = { vm.commitRestore(RestoreMode.REPLACE) },
                    onCancel = { vm.reset() }
                )
            }

            state.restoreResult?.let { result ->
                Spacer(Modifier.height(12.dp))
                ResultCard(
                    lines = listOf(
                        when (result.mode) {
                            RestoreMode.MERGE -> "按主键合并完成"
                            RestoreMode.REPLACE -> "整体替换完成"
                        },
                        "新增 ${result.txnAdded} 笔，覆盖 ${result.txnUpdated} 笔" +
                            if (result.txnRemoved > 0) "，清掉 ${result.txnRemoved} 笔" else "",
                        "分类 ${result.categoryCount} · 规则 ${result.ruleCount} · " +
                            "忽略 ${result.ignoreCount} · 批次 ${result.batchCount}",
                    ),
                    onDone = { vm.reset() }
                )
            }

            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "出错了",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = vm::dismissError) { Text("知道了") }
                    }
                }
            }

            state.notice?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    "✅ $message",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ── 卡片 ───────────────────────────────────────────────────────────

@Composable
private fun WarningCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "账本目前只有手机里这一份",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "本 App 不联网，数据也不会进系统云备份 —— 删了 App 或刷机，账本就没了。\n" +
                    "导出的备份是加密文件，存放在你自己选的位置，可以拷到电脑或网盘。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun ExportCard(busy: Boolean, exportSummaryText: String?, onExport: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("导出备份", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "把流水、分类、规则、忽略名单和导入批次打包成一个加密文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "处理中…" else "导出备份文件")
            }
            exportSummaryText?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RestoreCard(busy: Boolean, onPickFile: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("恢复备份", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "选择之前导出的 .jzbk 文件。解密后会先给你看内容摘要，再决定怎么落库。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onPickFile,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("选择备份文件") }
        }
    }
}

@Composable
private fun PreviewCard(
    summaryText: String,
    detailText: String,
    busy: Boolean,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "确认要恢复吗",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(summaryText, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                detailText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))

            Text(
                "合并：备份里有的流水按 id 覆盖，本机多余的保留。\n" +
                    "整体替换：先清空本机流水、忽略名单和导入批次，再写入备份。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onMerge,
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("合并") }
                Button(
                    onClick = onReplace,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("整体替换") }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun ResultCard(lines: List<String>, onDone: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "恢复完成",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(6.dp))
            lines.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onDone) { Text("完成") }
        }
    }
}

// ── 口令对话框 ─────────────────────────────────────────────────────

@Composable
private fun PasswordDialog(
    title: String,
    subtitle: String,
    requireConfirm: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val tooShort = password.length < MIN_PASSWORD_LENGTH
    val mismatch = requireConfirm && confirm != password
    val canSubmit = password.isNotEmpty() && (!requireConfirm || (!tooShort && !mismatch))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (requireConfirm) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("再输一次") },
                        singleLine = true,
                        isError = confirm.isNotEmpty() && mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            tooShort -> "至少 $MIN_PASSWORD_LENGTH 位"
                            mismatch -> "两次输入不一致"
                            else -> "建议用一句只有你记得住的话"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tooShort || mismatch) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password.toCharArray()) }, enabled = canSubmit) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private const val MIN_PASSWORD_LENGTH = 8

private fun defaultBackupFileName(): String {
    val today = Dates.dayKey(System.currentTimeMillis()).replace("-", "")
    return "jianzhang-backup-$today.jzbk"
}

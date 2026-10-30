package com.mizhii.ledger.ui.imports

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mizhii.ledger.data.import.BillFile
import com.mizhii.ledger.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账单导入页（M4）。
 *
 * 交互刻意做成"预览 → 确认"两步：账单是权威数据，一旦入库就覆盖通知通道记的账，
 * 所以导入前必须让用户看到**对账结果**（账单声明的汇总 vs 实际解析）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    viewModel: ImportViewModel = viewModel(factory = ImportViewModel.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayName(context, uri) ?: "账单文件"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取文件")
                    name to bytes
                }
            }
            loaded.onSuccess { (name, bytes) ->
                if (bytes.isEmpty()) {
                    // 极小文件肯定不是账单，但错误提示交给解析器统一给
                    viewModel.selectFile(name, bytes)
                } else {
                    viewModel.selectFile(name, bytes)
                }
            }.onFailure {
                viewModel.selectFile("账单文件", ByteArray(0))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入账单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // 外层 Scaffold 已经把系统栏 inset 吃掉并 padding 给了 NavHost，
                // 这里再吃一次就会多出一条状态栏高度的空白（其余 5 个二级页都关了这个）。
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
            Text(
                "支持微信「下载账单」导出的 xlsx / csv，以及支付宝导出的 csv。\n" +
                    "导入的是权威数据：与已有记录冲突时以账单为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            if (state.busy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Spacer(Modifier.height(0.dp))
                    Text("  处理中…", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(16.dp))
            }

            when {
                state.result != null -> ResultCard(state.result!!, onDone = viewModel::reset)
                state.preview != null -> PreviewCard(
                    preview = state.preview!!,
                    busy = state.busy,
                    onConfirm = viewModel::confirmImport,
                    onReselect = { picker.launch(BILL_MIME_TYPES); viewModel.reset() }
                )
                else -> {
                    Button(
                        onClick = { picker.launch(BILL_MIME_TYPES) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy
                    ) { Text(if (state.preview == null) "选择账单文件" else "重新选择") }
                }
            }

            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "无法导入",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            state.fileName?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    "文件：$it",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val BILL_MIME_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/csv",
    "text/comma-separated-values",
    "application/octet-stream",
    "*/*"
)

@Composable
private fun PreviewCard(
    preview: com.mizhii.ledger.domain.import.BillImporter.Preview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onReselect: () -> Unit
) {
    val parsed = preview.parsed
    val reconcile = parsed.computed()
    val problems = preview.reconcileProblems

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                when (parsed.kind) {
                    BillFile.Kind.WECHAT -> "微信支付账单"
                    BillFile.Kind.ALIPAY -> "支付宝账单"
                    BillFile.Kind.UNKNOWN -> "未识别的账单"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "编码 ${parsed.encoding} · 共 ${reconcile.totalCount} 笔",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))

            AmountLine("支出", reconcile.expenseCount, reconcile.expenseCents)
            AmountLine("收入", reconcile.incomeCount, reconcile.incomeCents)
            AmountLine("中性交易（不计收支）", reconcile.neutralCount, reconcile.neutralCents)

            if (problems.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "✅ 与账单声明的汇总一致",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Text(
                    "⚠️ 对账不一致（仍可导入，但请留意）：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                problems.forEach {
                    Text(
                        "· $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (preview.alreadyImported != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "⚠️ 这个文件已经导入过，不能重复导入",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConfirm,
                    enabled = !busy && preview.alreadyImported == null,
                    modifier = Modifier.fillMaxWidth(0.55f)
                ) { Text("确认导入") }
                OutlinedButton(onClick = onReselect, enabled = !busy) { Text("换一个") }
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: com.mizhii.ledger.domain.import.BillImporter.Result,
    onDone: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("导入完成", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("新增 ${result.newCount} 笔，合并到已有记录 ${result.mergedCount} 笔", style = MaterialTheme.typography.bodyMedium)
            Text(
                "跳过中性交易 ${result.neutralCount} 笔（提现等不计收支）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "支出 ${result.reconcile.expenseCount} 笔 ${Money.formatWithSymbol(result.reconcile.expenseCents)} · " +
                    "收入 ${result.reconcile.incomeCount} 笔 ${Money.formatWithSymbol(result.reconcile.incomeCents)}",
                style = MaterialTheme.typography.bodySmall
            )
            if (result.problems.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                result.problems.forEach {
                    Text(
                        "⚠️ $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onDone) { Text("完成") }
        }
    }
}

@Composable
private fun AmountLine(label: String, count: Int, cents: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            "$count 笔　${Money.formatWithSymbol(cents)}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
    }.getOrNull() ?: uri.lastPathSegment
}

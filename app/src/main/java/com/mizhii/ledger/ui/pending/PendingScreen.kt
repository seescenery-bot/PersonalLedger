package com.mizhii.ledger.ui.pending

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mizhii.ledger.R
import com.mizhii.ledger.data.db.entity.CategoryEntity
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.TxnEntity
import com.mizhii.ledger.ui.common.CategoryBadge
import com.mizhii.ledger.ui.common.EmptyState
import com.mizhii.ledger.ui.edit.channelDisplayName
import com.mizhii.ledger.ui.theme.AmountNormal
import com.mizhii.ledger.ui.theme.LocalLedgerExtraColors
import com.mizhii.ledger.util.Dates
import com.mizhii.ledger.util.Money

/**
 * 待确认队列（需求文档 §7.3）。
 * 置信度不足的自动记账落到这里，用户裁决一次：确认 / 改分类 / 忽略 / 删除。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PendingScreen(
    onBack: () -> Unit,
    viewModel: PendingViewModel = viewModel(factory = PendingViewModel.Factory)
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TxnEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pending_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        if (pending.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.pending_empty),
                description = stringResource(R.string.pending_empty_desc),
                modifier = Modifier.padding(padding).fillMaxSize()
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pending, key = { it.id }) { txn ->
                    PendingCard(
                        txn = txn,
                        category = categories.firstOrNull { it.id == txn.categoryId },
                        onConfirm = { viewModel.confirm(txn.id) },
                        onIgnore = { viewModel.ignore(txn.id) },
                        onDelete = { viewModel.delete(txn) },
                        onChangeCategory = { editing = txn }
                    )
                }
            }
        }
    }

    editing?.let { txn ->
        CategoryPickerDialog(
            categories = categories.filter {
                it.direction == null || it.direction == txn.direction
            },
            onDismiss = { editing = null },
            onPick = { categoryId ->
                viewModel.setCategory(txn.id, categoryId)
                editing = null
            }
        )
    }
}

@Composable
private fun PendingCard(
    txn: TxnEntity,
    category: CategoryEntity?,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    onDelete: () -> Unit,
    onChangeCategory: () -> Unit
) {
    val extra = LocalLedgerExtraColors.current
    // stringResource / channelDisplayName 都是 @Composable，必须在 lambda 外先取好
    val dayLabel = Dates.dayHeaderLabel(txn.dayKey)
    val channelName = channelDisplayName(txn.channel)
    val confidenceLabel = stringResource(R.string.pending_confidence, txn.confidence)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(category = category, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = txn.rawText ?: (category?.name ?: "—"),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$dayLabel · $channelName · $confidenceLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = Money.formatSigned(txn.amountCents, txn.direction),
                    style = AmountNormal,
                    color = if (txn.direction == Direction.EXPENSE) extra.expense else extra.income
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onChangeCategory) {
                    Text(stringResource(R.string.pending_change_category))
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.pending_confirm))
                }
                TextButton(onClick = onIgnore) {
                    Text(stringResource(R.string.pending_ignore))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.common_delete))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPickerDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.category_picker_title)) },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                categories.forEach { category ->
                    FilterChip(
                        selected = false,
                        onClick = { onPick(category.id) },
                        label = { Text(category.name) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

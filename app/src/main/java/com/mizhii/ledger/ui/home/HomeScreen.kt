package com.mizhii.ledger.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.TxnEntity
import com.mizhii.ledger.data.db.entity.TxnSource
import com.mizhii.ledger.ui.common.CategoryBadge
import com.mizhii.ledger.ui.common.EmptyState
import com.mizhii.ledger.ui.edit.TxnEditSheet
import com.mizhii.ledger.ui.edit.channelDisplayName
import com.mizhii.ledger.ui.theme.AmountNormal
import com.mizhii.ledger.ui.theme.LocalLedgerExtraColors
import com.mizhii.ledger.util.Dates
import com.mizhii.ledger.util.Money

/**
 * 流水列表页（文档 §7.1）。
 * 分日分组 + 月份切换 + 汇总条 + 左滑删除撤销 + FAB 记账。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPending: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showMonthPicker by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TxnEntity?>(null) }

    val deletedMessage = stringResource(R.string.home_deleted)
    val undoLabel = stringResource(R.string.common_undo)

    // 删除后给 5 秒撤销机会（§10.4）
    LaunchedEffect(deleted) {
        val target = deleted ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedMessage,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
        } else {
            viewModel.clearDeleted()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MonthHeader(
                monthKey = state.monthKey,
                onPrev = { viewModel.changeMonth(-1) },
                onNext = { viewModel.changeMonth(1) },
                onClickMonth = { showMonthPicker = true }
            )

            SummaryStrip(expenseCents = state.summary.expenseCents, incomeCents = state.summary.incomeCents)

            if (state.pendingCount > 0) {
                PendingBanner(count = state.pendingCount, onClick = onOpenPending)
            }

            if (state.groups.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = stringResource(R.string.home_empty_title),
                        description = stringResource(R.string.home_empty_desc)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    state.groups.forEach { group ->
                        stickyHeader(key = "header_${group.dayKey}") {
                            DayHeader(group = group)
                        }
                        items(items = group.items, key = { "txn_${it.id}" }) { txn ->
                            TxnRow(
                                txn = txn,
                                title = txn.counterparty
                                    ?: state.categoryName(txn.categoryId)
                                    ?: stringResource(R.string.edit_no_category),
                                category = state.category(txn.categoryId),
                                onClick = {
                                    editing = txn
                                    showEditor = true
                                },
                                onDelete = { viewModel.delete(txn) }
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                editing = null
                showEditor = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_add))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp, start = 16.dp, end = 16.dp)
        )
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

    if (showEditor) {
        TxnEditSheet(
            initial = editing,
            categories = state.categories,
            onDismiss = { showEditor = false },
            onSave = { request -> viewModel.save(request) }
        )
    }
}

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
            .padding(start = 8.dp, end = 8.dp),
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

@Composable
private fun SummaryStrip(expenseCents: Long, incomeCents: Long) {
    val extra = LocalLedgerExtraColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_expense),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = Money.formatWithSymbol(expenseCents),
                    style = AmountNormal,
                    color = extra.expense
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_income),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = Money.formatWithSymbol(incomeCents),
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
                    text = Money.formatWithSymbol(incomeCents - expenseCents),
                    style = AmountNormal,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun PendingBanner(count: Int, onClick: () -> Unit) {
    val extra = LocalLedgerExtraColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(
                color = extra.warning.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.home_pending_banner, count),
            style = MaterialTheme.typography.bodyMedium,
            color = extra.warning
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = extra.warning
        )
    }
}

@Composable
private fun DayHeader(group: DayGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = Dates.dayHeaderLabel(group.dayKey),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        if (group.expenseCents > 0) {
            Text(
                text = stringResource(
                    R.string.home_day_expense,
                    Money.formatWithSymbol(group.expenseCents)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (group.expenseCents > 0 && group.incomeCents > 0) {
            Spacer(Modifier.width(8.dp))
        }
        if (group.incomeCents > 0) {
            Text(
                text = stringResource(
                    R.string.home_day_income,
                    Money.formatWithSymbol(group.incomeCents)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TxnRow(
    txn: TxnEntity,
    title: String,
    category: com.mizhii.ledger.data.db.entity.CategoryEntity?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val extra = LocalLedgerExtraColors.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryBadge(category = category)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val channelName = channelDisplayName(txn.channel)
                    Text(
                        text = "${Dates.timeLabel(txn.occurredAt)} · $channelName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (txn.source != TxnSource.MANUAL) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.home_auto_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = Money.formatSigned(txn.amountCents, txn.direction),
                style = AmountNormal,
                color = if (txn.direction == Direction.EXPENSE) extra.expense else extra.income
            )
        }
    }
}

package com.mizhii.ledger.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mizhii.ledger.R
import com.mizhii.ledger.data.db.entity.CategoryEntity
import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.TxnEntity
import com.mizhii.ledger.ui.CategoryIcons
import com.mizhii.ledger.ui.theme.AmountLarge
import com.mizhii.ledger.ui.theme.LocalLedgerExtraColors
import com.mizhii.ledger.util.Dates
import com.mizhii.ledger.util.Money

/**
 * 记账 / 编辑 Sheet（文档 §7.2 / §10.4）。
 * 金额使用自定义数字键盘，不弹系统键盘；商户与备注才用 IME。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TxnEditSheet(
    initial: TxnEntity?,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (TxnEditRequest) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val extra = LocalLedgerExtraColors.current

    var amountText by rememberSaveable {
        mutableStateOf(initial?.let { Money.toInputText(it.amountCents) } ?: "")
    }
    var direction by rememberSaveable { mutableStateOf<Direction>(initial?.direction ?: Direction.EXPENSE) }
    var channel by rememberSaveable { mutableStateOf<Channel>(initial?.channel ?: Channel.WECHAT) }
    var categoryId by rememberSaveable { mutableStateOf(initial?.categoryId) }
    var occurredAt by rememberSaveable {
        mutableLongStateOf(initial?.occurredAt ?: System.currentTimeMillis())
    }
    var counterparty by rememberSaveable { mutableStateOf(initial?.counterparty ?: "") }
    var note by rememberSaveable { mutableStateOf(initial?.note ?: "") }
    var showDatePicker by remember { mutableStateOf(false) }

    val amountCents = Money.parseYuanToCents(amountText) ?: 0L
    val canSave = amountCents > 0

    val visibleCategories = remember(categories, direction) {
        categories.filter { it.direction == null || it.direction == direction }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                // targetSdk 36 强制 edge-to-edge：输入法不再由系统自动把窗口顶上去，
                // 必须自己吃 IME inset，否则「商户 / 备注」输入框会被键盘盖住。
                .imePadding()
                .padding(horizontal = 20.dp)
        ) {
            // ---------- 可滚动区 ----------
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(
                        if (initial == null) R.string.edit_title_new else R.string.edit_title_edit
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))

                // 方向
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Direction.entries.forEach { d ->
                        FilterChip(
                            selected = direction == d,
                            onClick = {
                                if (direction != d) {
                                    direction = d
                                    // 切方向后若已选分类不属于该方向，清空选择
                                    val selected = categories.firstOrNull { it.id == categoryId }
                                    if (selected?.direction != null && selected.direction != d) {
                                        categoryId = null
                                    }
                                }
                            },
                            label = {
                                Text(
                                    stringResource(
                                        if (d == Direction.EXPENSE) R.string.home_expense
                                        else R.string.home_income
                                    )
                                )
                            }
                        )
                    }
                }

                // 金额
                Spacer(Modifier.height(4.dp))
                Text(
                    text = Money.SYMBOL + if (amountText.isEmpty()) "0.00" else amountText,
                    style = AmountLarge,
                    color = if (direction == Direction.EXPENSE) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        extra.income
                    }
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 分类
                SectionLabel(stringResource(R.string.edit_category))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    visibleCategories.forEach { category ->
                        FilterChip(
                            selected = categoryId == category.id,
                            onClick = {
                                categoryId = if (categoryId == category.id) null else category.id
                            },
                            label = { Text(category.name) },
                            leadingIcon = {
                                Icon(
                                    imageVector = CategoryIcons.of(category.iconKey),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 通道
                SectionLabel(stringResource(R.string.edit_channel))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Channel.entries.forEach { c ->
                        FilterChip(
                            selected = channel == c,
                            onClick = { channel = c },
                            label = { Text(channelLabel(c)) }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 时间（不加分区标题，避免在滚动区域底部出现孤立标题）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = { occurredAt = System.currentTimeMillis() },
                        label = { Text(stringResource(R.string.edit_today)) }
                    )
                    AssistChip(
                        onClick = {
                            occurredAt = System.currentTimeMillis() - 24L * 3600L * 1000L
                        },
                        label = { Text(stringResource(R.string.edit_yesterday)) }
                    )
                    AssistChip(
                        onClick = { showDatePicker = true },
                        label = { Text(Dates.dateTimeLabel(occurredAt)) }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 商户
                OutlinedTextField(
                    value = counterparty,
                    onValueChange = { if (it.length <= 50) counterparty = it },
                    label = { Text(stringResource(R.string.edit_merchant)) },
                    placeholder = { Text(stringResource(R.string.edit_merchant_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                // 备注
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 200) note = it },
                    label = { Text(stringResource(R.string.edit_note)) },
                    placeholder = { Text(stringResource(R.string.edit_note_hint)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))
            }

            // ---------- 固定区：键盘 + 保存 ----------
            AmountKeypad(
                onDigit = { digit ->
                    val base = if (amountText == "0") "" else amountText
                    val candidate = base + digit
                    if (Money.isPartialInputValid(candidate)) amountText = candidate
                },
                onDot = {
                    if (!amountText.contains('.')) {
                        amountText = if (amountText.isEmpty()) "0." else "$amountText."
                    }
                },
                onBackspace = {
                    if (amountText.isNotEmpty()) amountText = amountText.dropLast(1)
                }
            )

            Button(
                onClick = {
                    if (!canSave) return@Button
                    onSave(
                        TxnEditRequest(
                            id = initial?.id,
                            amountCents = amountCents,
                            direction = direction,
                            channel = channel,
                            categoryId = categoryId,
                            occurredAt = occurredAt,
                            counterparty = counterparty,
                            note = note
                        )
                    )
                    onDismiss()
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Text(stringResource(R.string.common_save))
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = Dates.toPickerMillis(Dates.toLocalDate(occurredAt))
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        occurredAt = Dates.withDate(occurredAt, Dates.localDateFromPickerMillis(millis))
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun channelLabel(channel: Channel): String = stringResource(
    when (channel) {
        Channel.WECHAT -> R.string.channel_wechat
        Channel.ALIPAY -> R.string.channel_alipay
        Channel.CASH -> R.string.channel_cash
        Channel.BANK -> R.string.channel_bank
        Channel.OTHER -> R.string.channel_other
    }
)

/** 供列表页复用的通道名（保持与编辑页文案一致） */
@Composable
fun channelDisplayName(channel: Channel): String = channelLabel(channel)

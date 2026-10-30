package com.mizhii.ledger.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mizhii.ledger.R
import com.mizhii.ledger.domain.capture.NotificationParser
import com.mizhii.ledger.domain.model.ParseOutcome
import com.mizhii.ledger.util.Money

/**
 * 解析测试台（需求文档 §7.8）。
 *
 * 这是让项目「可长期维护」的关键工具：微信/支付宝一改文案，用户粘一段文本进来，
 * 立刻能看到是否被正确解析、被什么原因丢弃，不用改代码也不用重新打日志。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParserLabScreen(onBack: () -> Unit) {
    val channels = listOf(
        "com.tencent.mm" to "微信",
        "com.eg.android.AlipayGphone" to "支付宝"
    )
    var text by rememberSaveable { mutableStateOf("") }
    var channelIndex by rememberSaveable { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<ParseOutcome?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lab_title)) },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.lab_channel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                channels.forEachIndexed { index, pair ->
                    FilterChip(
                        selected = channelIndex == index,
                        onClick = { channelIndex = index },
                        label = { Text(pair.second) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.lab_input_label)) },
                placeholder = { Text(stringResource(R.string.lab_input_hint)) },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        result = NotificationParser.parse(
                            packageName = channels[channelIndex].first,
                            title = channels[channelIndex].second,
                            body = text,
                            postTime = System.currentTimeMillis()
                        )
                    },
                    enabled = text.isNotBlank()
                ) { Text(stringResource(R.string.lab_parse)) }
                TextButton(onClick = {
                    text = ""
                    result = null
                }) { Text(stringResource(R.string.lab_clear)) }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            when (val r = result) {
                null -> Text(
                    text = stringResource(R.string.lab_input_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is ParseOutcome.Discarded -> {
                    Text(
                        text = stringResource(R.string.lab_discard) + "  " + r.reason,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    FieldRow(stringResource(R.string.lab_normalized), r.normalizedText)
                }

                is ParseOutcome.Success -> {
                    val c = r.candidate
                    Text(
                        text = stringResource(R.string.lab_success),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    FieldRow(
                        stringResource(R.string.lab_field_amount),
                        Money.formatWithSymbol(c.amountCents)
                    )
                    FieldRow(
                        stringResource(R.string.lab_field_direction),
                        if (c.direction == com.mizhii.ledger.data.db.entity.Direction.EXPENSE) {
                            stringResource(R.string.home_expense)
                        } else {
                            stringResource(R.string.home_income)
                        }
                    )
                    FieldRow(stringResource(R.string.lab_field_channel), c.channel.name)
                    FieldRow(
                        stringResource(R.string.lab_field_counterparty),
                        c.counterparty ?: stringResource(R.string.lab_none)
                    )
                    FieldRow(
                        stringResource(R.string.lab_field_external_no),
                        c.externalNo ?: stringResource(R.string.lab_none)
                    )
                    FieldRow(
                        stringResource(R.string.lab_field_suggested),
                        c.suggestedCategoryId ?: stringResource(R.string.lab_none)
                    )
                    FieldRow(stringResource(R.string.lab_normalized), c.rawText.orEmpty())
                }
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.35f)
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

package com.mizhii.ledger.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mizhii.ledger.R
import com.mizhii.ledger.util.Dates

/**
 * 月份选择弹窗（文档 §7.1：点击顶部月份弹出月份网格）。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MonthPickerDialog(
    initialMonthKey: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    var year by remember { mutableIntStateOf(Dates.yearOf(initialMonthKey)) }
    val selectedMonth = Dates.monthOf(initialMonthKey)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = { year -= 1 }) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null)
                }
                Text(
                    text = stringResource(R.string.year_format, year),
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { year += 1 }) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        },
        text = {
            Column {
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (m in 1..12) {
                        FilterChip(
                            selected = (m == selectedMonth && year == Dates.yearOf(initialMonthKey)),
                            onClick = {
                                val monthKey = "${year}-${m.toString().padStart(2, '0')}"
                                onSelected(monthKey)
                            },
                            label = { Text(stringResource(R.string.month_number_format, m)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

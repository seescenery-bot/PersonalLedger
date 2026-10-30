package com.mizhii.ledger.ui.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mizhii.ledger.R
import com.mizhii.ledger.data.db.entity.CategoryEntity
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.ui.CategoryIcons
import com.mizhii.ledger.ui.common.CategoryBadge

/**
 * 分类管理（文档 §7.4）：内置分类只读+可删自定义，删除前把流水迁移到默认分类。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryManageScreen(
    onBack: () -> Unit,
    viewModel: CategoryViewModel = viewModel(factory = CategoryViewModel.Factory)
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CategoryEntity?>(null) }

    val expenseCategories = categories.filter { it.direction == Direction.EXPENSE }
    val incomeCategories = categories.filter { it.direction == Direction.INCOME }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.category_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showCreate = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.category_add)
                        )
                    }
                },
                // 顶部状态栏 inset 已由根 Scaffold 的 innerPadding 提供，这里不能重复加
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item { SectionHeader(stringResource(R.string.category_expense_section)) }
            items(expenseCategories, key = { it.id }) { category ->
                CategoryRow(category = category, onDelete = { pendingDelete = category })
            }
            item { SectionHeader(stringResource(R.string.category_income_section)) }
            items(incomeCategories, key = { it.id }) { category ->
                CategoryRow(category = category, onDelete = { pendingDelete = category })
            }
        }
    }

    if (showCreate) {
        CreateCategoryDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, direction, iconKey ->
                val palette = listOf(
                    "#EE7B4D", "#4A90D9", "#C060A1", "#7E9B3F", "#8D6E63", "#3F8F8F",
                    "#9B6BD6", "#D9534F", "#5B8DEF", "#2E9E63", "#B8860B", "#78909C"
                )
                val color = palette[(name.hashCode() and 0x7FFFFFFF) % palette.size]
                viewModel.create(name, direction, iconKey, color)
            }
        )
    }

    pendingDelete?.let { category ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.category_delete_title)) },
            text = { Text(stringResource(R.string.category_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(category)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun CategoryRow(category: CategoryEntity, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CategoryBadge(category = category, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Text(text = category.name, style = MaterialTheme.typography.bodyLarge)
        if (category.isBuiltin) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.category_builtin_tag),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        if (!category.isBuiltin) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, direction: Direction, iconKey: String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var direction by rememberSaveable { mutableStateOf<Direction>(Direction.EXPENSE) }
    var iconKey by rememberSaveable { mutableStateOf("more_horiz") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.category_new_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 8) name = it },
                    label = { Text(stringResource(R.string.category_name_label)) },
                    placeholder = { Text(stringResource(R.string.category_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Direction.entries.forEach { d ->
                        FilterChip(
                            selected = direction == d,
                            onClick = { direction = d },
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
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.category_icon_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CategoryIcons.availableKeys.forEach { key ->
                        IconButton(
                            onClick = { iconKey = key },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = CategoryIcons.of(key),
                                contentDescription = null,
                                tint = if (key == iconKey) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(name.trim(), direction, iconKey)
                    onDismiss()
                },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

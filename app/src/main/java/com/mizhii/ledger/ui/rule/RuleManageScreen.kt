package com.mizhii.ledger.ui.rule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mizhii.ledger.R
import com.mizhii.ledger.data.db.entity.RuleEntity
import com.mizhii.ledger.ui.common.EmptyState

/**
 * 规则管理（文档 §6.7）：关键词 → 分类的映射。内置规则不可删但可禁用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleManageScreen(
    onBack: () -> Unit,
    viewModel: RuleViewModel = viewModel(factory = RuleViewModel.Factory)
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val categoryNames = categories.associate { it.id to it.name }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rule_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                // 顶部状态栏 inset 已由根 Scaffold 的 innerPadding 提供，这里不能重复加
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        if (rules.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.rule_empty),
                description = stringResource(R.string.rule_empty_desc),
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            )
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(rules, key = { it.id }) { rule ->
                    RuleRow(
                        rule = rule,
                        categoryName = categoryNames[rule.categoryId],
                        onToggle = { enabled -> viewModel.setEnabled(rule, enabled) },
                        onDelete = { viewModel.delete(rule) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: RuleEntity,
    categoryName: String?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.rule_contains_pattern,
                    rule.pattern,
                    categoryName ?: rule.categoryId
                ),
                style = MaterialTheme.typography.bodyLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.rule_hit_count, rule.hitCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (rule.isBuiltin) {
                    Text(
                        text = stringResource(R.string.rule_builtin_tag),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (!rule.isBuiltin) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
    }
}

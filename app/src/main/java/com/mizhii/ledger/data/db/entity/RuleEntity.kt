package com.mizhii.ledger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 自动分类规则表（文档 §6.7.2）。
 * priority 越小越先匹配：手动=0 / 用户规则 1-999 / 内置 5000-5999 / 兜底 9999。
 */
@Entity(
    tableName = "rule",
    indices = [Index("priority"), Index("enabled"), Index("categoryId")]
)
data class RuleEntity(
    @PrimaryKey val id: String,
    val priority: Int,
    val matchField: MatchField,
    val matchType: MatchType,
    val pattern: String,
    val categoryId: String,
    val enabled: Boolean = true,
    val isBuiltin: Boolean = false,
    val hitCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

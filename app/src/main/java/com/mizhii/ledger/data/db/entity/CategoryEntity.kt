package com.mizhii.ledger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 分类表（文档 §8.3）。最多二级：parentId 非空即为子分类。
 */
@Entity(
    tableName = "category",
    indices = [Index("parentId"), Index("sortOrder")]
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val direction: Direction? = null,
    val iconKey: String,
    val colorHex: String,
    val sortOrder: Int,
    val isBuiltin: Boolean = false,
    val isArchived: Boolean = false
)

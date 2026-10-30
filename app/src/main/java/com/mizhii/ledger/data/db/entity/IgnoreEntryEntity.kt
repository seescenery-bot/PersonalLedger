package com.mizhii.ledger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 忽略名单（文档 §8.7）。用户删除过的自动流水，其 externalNo 记入此处，
 * 防止下次导入 CSV 时又被加回来。
 */
@Entity(
    tableName = "ignore_entry",
    indices = [Index(value = ["externalNo"], unique = true)]
)
data class IgnoreEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val externalNo: String,
    val channel: Channel,
    val reason: String? = null,
    val createdAt: Long
)

package com.mizhii.ledger.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 账单导入批次（文档 §8.6）。fileSha256 唯一，用于"同一文件重复导入"幂等校验。
 */
@Entity(tableName = "import_batch")
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val channel: Channel,
    val fileName: String,
    val fileSha256: String,
    val encoding: String,
    val rowCount: Int,
    val newCount: Int,
    val mergedCount: Int,
    val skippedCount: Int,
    val importedAt: Long
)

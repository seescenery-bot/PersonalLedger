package com.mizhii.ledger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 原始捕获日志（文档 §8.5）。用于诊断与"是否漏记"统计，保留 7 天。
 */
@Entity(
    tableName = "raw_capture",
    indices = [Index("capturedAt"), Index("dedupeHash")]
)
data class RawCaptureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val channel: Channel,
    val source: TxnSource,
    val rawText: String,
    val capturedAt: Long,
    val dedupeHash: String,
    val parsedOk: Boolean,
    val discardReason: String? = null,
    val txnId: Long? = null
)

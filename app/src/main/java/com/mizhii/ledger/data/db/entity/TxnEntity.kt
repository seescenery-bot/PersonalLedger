package com.mizhii.ledger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 流水表（文档 §8.2）。
 *
 * 金额约定：amountCents 为"分"，永远 > 0，方向由 direction 表达。
 * 幂等约定：rowHash 唯一索引。自动来源用内容哈希，手动来源用 UUID（§6.5.4）。
 */
@Entity(
    tableName = "txn",
    indices = [
        Index("dayKey"),
        Index("occurredAt"),
        Index(value = ["channel", "externalNo"], unique = true),
        Index("status"),
        Index("categoryId"),
        Index(value = ["rowHash"], unique = true)
    ]
)
data class TxnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val occurredAt: Long,
    val dayKey: String,
    val amountCents: Long,
    val direction: Direction,
    val channel: Channel,

    val counterparty: String? = null,
    val goods: String? = null,
    val externalNo: String? = null,
    val merchantNo: String? = null,
    val payMethod: String? = null,

    val categoryId: String? = null,
    val categorySource: CategorySource = CategorySource.NONE,
    val note: String? = null,

    val source: TxnSource = TxnSource.MANUAL,
    val sourceDetail: String? = null,
    val confidence: Int = 100,
    val status: TxnStatus = TxnStatus.CONFIRMED,
    val timePrecision: TimePrecision = TimePrecision.INSTANT,

    val rawText: String? = null,
    val rowHash: String,
    val importBatchId: String? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

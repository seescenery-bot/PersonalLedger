package com.mizhii.ledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.TxnEntity
import kotlinx.coroutines.flow.Flow

/** 月度汇总投影（§7.5 统计口径：仅统计 CONFIRMED） */
data class MonthSummary(
    val expenseCents: Long,
    val incomeCents: Long
)

@Dao
interface TxnDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(txn: TxnEntity): Long

    @Update
    suspend fun update(txn: TxnEntity)

    @Query("DELETE FROM txn WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun findById(id: Long): TxnEntity?

    /** 去重第一步：精确键（§6.5.2） */
    @Query("SELECT * FROM txn WHERE channel = :channel AND externalNo = :externalNo LIMIT 1")
    suspend fun findByExternalNo(channel: Channel, externalNo: String): TxnEntity?

    /** 幂等键（§6.5.4） */
    @Query("SELECT * FROM txn WHERE rowHash = :rowHash LIMIT 1")
    suspend fun findByRowHash(rowHash: String): TxnEntity?

    /** 去重第二步：模糊键候选集（§6.5.2），由调用方在内存里做时间窗口判定 */
    @Query(
        """
        SELECT * FROM txn
        WHERE channel = :channel
          AND direction = :direction
          AND amountCents = :amountCents
          AND occurredAt BETWEEN :fromInclusive AND :toInclusive
        """
    )
    suspend fun findFuzzyCandidates(
        channel: Channel,
        direction: String,
        amountCents: Long,
        fromInclusive: Long,
        toInclusive: Long
    ): List<TxnEntity>

    @Query("SELECT * FROM txn WHERE dayKey BETWEEN :fromDay AND :toDay ORDER BY occurredAt DESC")
    fun observeRange(fromDay: String, toDay: String): Flow<List<TxnEntity>>

    @Query("SELECT * FROM txn WHERE status = 'PENDING' ORDER BY occurredAt DESC")
    fun observePending(): Flow<List<TxnEntity>>

    @Query("UPDATE txn SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, updatedAt: Long)

    @Query("UPDATE txn SET categoryId = :categoryId, categorySource = :categorySource, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setCategory(
        id: Long,
        categoryId: String?,
        categorySource: String,
        updatedAt: Long
    )

    /**
     * 修复时间明显不合理的历史账目。
     *
     * 真机事故（2026-10-30 07:42，微信支付 ¥20.00）：无障碍通道把
     * `AccessibilityEvent.getEventTime()`（**uptimeMillis，开机以来毫秒**）当成 epoch 传给了
     * `occurredAt`，于是账目落到了 1970-01-17 —— 用户在当月列表里根本看不到，看起来就像"没记上"。
     *
     * `createdAt` 是落库时刻，与真实支付时间只差几秒，用它回填。
     * 幂等：修过之后 occurredAt 就不再小于 cutoff，重复执行不会再次命中。
     *
     * @return 修复行数
     */
    @Query(
        """
        UPDATE txn SET
            occurredAt = createdAt,
            dayKey = strftime('%Y-%m-%d', createdAt / 1000, 'unixepoch', 'localtime'),
            updatedAt = :now
        WHERE occurredAt < :cutoff
        """
    )
    suspend fun repairImplausibleDates(cutoff: Long, now: Long): Int

    @Query("SELECT COUNT(*) FROM txn WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN direction = 'EXPENSE' THEN amountCents ELSE 0 END), 0) AS expenseCents,
               COALESCE(SUM(CASE WHEN direction = 'INCOME' THEN amountCents ELSE 0 END), 0) AS incomeCents
        FROM txn
        WHERE status = 'CONFIRMED' AND dayKey LIKE :monthPrefix || '%'
        """
    )
    fun observeMonthSummary(monthPrefix: String): Flow<MonthSummary>

    @Query(
        """
        SELECT categoryId, SUM(amountCents) AS totalCents, COUNT(*) AS count
        FROM txn
        WHERE status = 'CONFIRMED' AND direction = 'EXPENSE' AND dayKey LIKE :monthPrefix || '%'
        GROUP BY categoryId
        ORDER BY totalCents DESC
        """
    )
    fun observeCategoryBreakdown(monthPrefix: String): Flow<List<CategoryTotal>>

    /** 渠道分布（§7.5） */
    @Query(
        """
        SELECT channel, SUM(amountCents) AS totalCents, COUNT(*) AS count
        FROM txn
        WHERE status = 'CONFIRMED' AND direction = 'EXPENSE' AND dayKey LIKE :monthPrefix || '%'
        GROUP BY channel
        ORDER BY totalCents DESC
        """
    )
    fun observeChannelBreakdown(monthPrefix: String): Flow<List<ChannelTotal>>

    /** 每日支出趋势（§7.5） */
    @Query(
        """
        SELECT dayKey, SUM(amountCents) AS expenseCents
        FROM txn
        WHERE status = 'CONFIRMED' AND direction = 'EXPENSE' AND dayKey LIKE :monthPrefix || '%'
        GROUP BY dayKey
        ORDER BY dayKey
        """
    )
    fun observeDailyExpense(monthPrefix: String): Flow<List<DailyTotal>>

    /** 高频商户 Top N（§7.5） */
    @Query(
        """
        SELECT counterparty, SUM(amountCents) AS totalCents, COUNT(*) AS count
        FROM txn
        WHERE status = 'CONFIRMED' AND direction = 'EXPENSE'
          AND counterparty IS NOT NULL AND counterparty != ''
          AND dayKey LIKE :monthPrefix || '%'
        GROUP BY counterparty
        ORDER BY totalCents DESC
        LIMIT :limit
        """
    )
    fun observeTopMerchants(monthPrefix: String, limit: Int = 5): Flow<List<MerchantTotal>>

    // ── 备份 / 恢复（M5 §7.6）──────────────────────────────────────

    @Query("SELECT * FROM txn ORDER BY id ASC")
    suspend fun getAll(): List<TxnEntity>

    /** 恢复时必须保留原 id：待确认通知、忽略名单、导入批次都按 id 关联 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(items: List<TxnEntity>)

    @Query("DELETE FROM txn")
    suspend fun deleteAll()

    // ── 桌面小部件（M6）────────────────────────────────────────────
    // 小部件是一次性读快照，不能用 Flow，所以单独给 suspend 版本。

    @Query(
        """
        SELECT IFNULL(SUM(CASE WHEN direction = 'EXPENSE' THEN amountCents ELSE 0 END), 0) AS expenseCents,
               IFNULL(SUM(CASE WHEN direction = 'INCOME'  THEN amountCents ELSE 0 END), 0) AS incomeCents
        FROM txn
        WHERE status = 'CONFIRMED' AND dayKey LIKE :monthPrefix || '%'
        """
    )
    suspend fun monthSummaryOnce(monthPrefix: String): MonthSummary

    @Query("SELECT COUNT(*) FROM txn WHERE status = 'CONFIRMED' AND dayKey LIKE :monthPrefix || '%'")
    suspend fun monthTxnCount(monthPrefix: String): Int

    @Query("SELECT * FROM txn WHERE status = 'CONFIRMED' ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recentConfirmed(limit: Int): List<TxnEntity>
}

data class CategoryTotal(
    val categoryId: String?,
    val totalCents: Long,
    val count: Int
)

data class ChannelTotal(
    val channel: String,
    val totalCents: Long,
    val count: Int
)

data class DailyTotal(
    val dayKey: String,
    val expenseCents: Long
)

data class MerchantTotal(
    val counterparty: String,
    val totalCents: Long,
    val count: Int
)

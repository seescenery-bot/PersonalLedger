package com.mizhii.ledger.data.repo

import com.mizhii.ledger.data.db.dao.MonthSummary
import com.mizhii.ledger.data.db.dao.TxnDao
import com.mizhii.ledger.data.db.entity.CategorySource
import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.TimePrecision
import com.mizhii.ledger.data.db.entity.TxnEntity
import com.mizhii.ledger.data.db.entity.TxnSource
import com.mizhii.ledger.data.db.entity.TxnStatus
import com.mizhii.ledger.util.Dates
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class TxnRepository(private val txnDao: TxnDao) {

    fun observeMonth(monthKey: String): Flow<List<TxnEntity>> =
        txnDao.observeRange(Dates.firstDayOfMonth(monthKey), Dates.lastDayOfMonth(monthKey))

    fun observeMonthSummary(monthKey: String): Flow<MonthSummary> =
        txnDao.observeMonthSummary(monthKey)

    fun observePendingCount(): Flow<Int> = txnDao.observePendingCount()

    // ── 统计（§7.5）────────────────────────────────────────────────
    fun observeCategoryBreakdown(monthKey: String) = txnDao.observeCategoryBreakdown(monthKey)

    fun observeChannelBreakdown(monthKey: String) = txnDao.observeChannelBreakdown(monthKey)

    fun observeDailyExpense(monthKey: String) = txnDao.observeDailyExpense(monthKey)

    fun observeTopMerchants(monthKey: String, limit: Int = 5) =
        txnDao.observeTopMerchants(monthKey, limit)

    /** 待确认队列（§7.3） */
    fun observePending(): Flow<List<TxnEntity>> = txnDao.observePending()

    /** 确认一笔待确认流水（保留当前分类） */
    suspend fun confirm(id: Long) =
        txnDao.setStatus(id, TxnStatus.CONFIRMED.name, System.currentTimeMillis())

    /** 忽略：不出现在列表与统计，但保留记录（§7.3） */
    suspend fun ignore(id: Long) =
        txnDao.setStatus(id, TxnStatus.IGNORED.name, System.currentTimeMillis())

    /** 修改分类：categorySource 升级为 MANUAL，永不被规则覆盖（§6.7.1） */
    suspend fun setCategory(id: Long, categoryId: String?) =
        txnDao.setCategory(id, categoryId, CategorySource.MANUAL.name, System.currentTimeMillis())

    /** 手动新增（文档 §7.2）。手动流水一律 CONFIRMED，rowHash 用 UUID 避免唯一索引误合并（§6.5.4）。 */
    suspend fun insertManual(input: TxnInput): Long {
        val now = System.currentTimeMillis()
        val entity = TxnEntity(
            occurredAt = input.occurredAt,
            dayKey = Dates.dayKey(input.occurredAt),
            amountCents = input.amountCents,
            direction = input.direction,
            channel = input.channel,
            counterparty = input.counterparty.cleaned(),
            goods = null,
            categoryId = input.categoryId,
            categorySource = if (input.categoryId != null) CategorySource.MANUAL else CategorySource.NONE,
            note = input.note.cleaned(),
            source = TxnSource.MANUAL,
            confidence = 100,
            status = TxnStatus.CONFIRMED,
            timePrecision = TimePrecision.INSTANT,
            rowHash = "manual_${UUID.randomUUID()}",
            createdAt = now,
            updatedAt = now
        )
        return txnDao.insert(entity)
    }

    /** 编辑已有流水：分类被改动时 categorySource 升级为 MANUAL，永不被规则覆盖（§6.7.1） */
    suspend fun updateFromEdit(id: Long, input: TxnInput): Boolean {
        val existing = txnDao.findById(id) ?: return false
        val categoryChanged = existing.categoryId != input.categoryId
        val updated = existing.copy(
            occurredAt = input.occurredAt,
            dayKey = Dates.dayKey(input.occurredAt),
            amountCents = input.amountCents,
            direction = input.direction,
            channel = input.channel,
            counterparty = input.counterparty.cleaned(),
            categoryId = input.categoryId,
            categorySource = when {
                categoryChanged && input.categoryId != null -> CategorySource.MANUAL
                else -> existing.categorySource
            },
            note = input.note.cleaned(),
            updatedAt = System.currentTimeMillis()
        )
        txnDao.update(updated)
        return true
    }

    suspend fun delete(id: Long) = txnDao.deleteById(id)

    /** 撤销删除：带原 id 重新插入，保证撤销后 id 与关联关系不变 */
    suspend fun restore(entity: TxnEntity) = txnDao.insert(entity)

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

/** 手动记账 / 编辑的输入模型 */
data class TxnInput(
    val amountCents: Long,
    val direction: Direction,
    val channel: Channel,
    val categoryId: String?,
    val occurredAt: Long,
    val counterparty: String? = null,
    val note: String? = null
)

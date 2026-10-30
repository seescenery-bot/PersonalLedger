package com.mizhii.ledger.domain.capture

import com.mizhii.ledger.data.db.dao.TxnDao
import com.mizhii.ledger.data.db.entity.TxnEntity
import com.mizhii.ledger.data.db.entity.TxnSource
import com.mizhii.ledger.data.db.entity.TxnStatus
import com.mizhii.ledger.domain.model.TxnCandidate

/**
 * 去重合并（需求文档 §6.5.2 / §6.5.3）。
 *
 * 两步：
 *  1. 精确键：有 externalNo 时按 (channel, externalNo) 唯一匹配
 *  2. 模糊键：同渠道 + 同方向 + 金额相等 + 时间窗口内；若双方 counterparty 都非空且不同，则不算同一笔
 */
class Deduper(private val txnDao: TxnDao) {

    suspend fun findMatch(candidate: TxnCandidate, windowSeconds: Long): TxnEntity? {
        val externalNo = candidate.externalNo
        if (!externalNo.isNullOrBlank()) {
            txnDao.findByExternalNo(candidate.channel, externalNo)?.let { return it }
        }
        val windowMs = windowSeconds.coerceAtLeast(1) * 1000L
        val nearby = txnDao.findFuzzyCandidates(
            channel = candidate.channel,
            direction = candidate.direction.name,
            amountCents = candidate.amountCents,
            fromInclusive = candidate.occurredAt - windowMs,
            toInclusive = candidate.occurredAt + windowMs
        )
        return nearby.firstOrNull { existing ->
            // 双方都有商户名且不同 → 视为不同笔（保护：同日同额两笔不同商户）
            val a = candidate.counterparty
            val b = existing.counterparty
            a.isNullOrBlank() || b.isNullOrBlank() || a == b
        }
    }

    /** 合并策略（§6.5.3）：非空优先，来源取并集，CSV 权威 */
    fun merge(existing: TxnEntity, candidate: TxnCandidate, mergedScore: Int): TxnEntity {
        val mergedSources = (existing.sourceDetail ?: existing.source.name)
            .split("+")
            .filter { it.isNotBlank() }
            .plus(candidate.source.name)
            .distinct()
        return existing.copy(
            counterparty = existing.counterparty ?: candidate.counterparty,
            goods = existing.goods ?: candidate.goods,
            externalNo = existing.externalNo ?: candidate.externalNo,
            merchantNo = existing.merchantNo ?: candidate.merchantNo,
            payMethod = existing.payMethod ?: candidate.payMethod,
            rawText = existing.rawText ?: candidate.rawText,
            source = TxnSource.MERGED,
            sourceDetail = mergedSources.joinToString("+"),
            confidence = maxOf(existing.confidence, mergedScore),
            status = if (candidate.source == TxnSource.CSV) TxnStatus.CONFIRMED else existing.status,
            updatedAt = System.currentTimeMillis()
        )
    }
}

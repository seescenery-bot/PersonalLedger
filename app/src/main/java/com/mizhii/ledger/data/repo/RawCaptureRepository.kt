package com.mizhii.ledger.data.repo

import com.mizhii.ledger.data.db.dao.RawCaptureDao
import com.mizhii.ledger.data.db.entity.RawCaptureEntity
import kotlinx.coroutines.flow.Flow

/** 原始捕获日志（§7.8 诊断页 / §8.5） */
class RawCaptureRepository(private val rawCaptureDao: RawCaptureDao) {

    fun observeRecent(limit: Int = 200): Flow<List<RawCaptureEntity>> =
        rawCaptureDao.observeRecent(limit)

    suspend fun clearOlderThan(days: Int): Int {
        val cutoff = System.currentTimeMillis() - days.coerceAtLeast(1) * 24L * 3600L * 1000L
        return rawCaptureDao.deleteOlderThan(cutoff)
    }
}

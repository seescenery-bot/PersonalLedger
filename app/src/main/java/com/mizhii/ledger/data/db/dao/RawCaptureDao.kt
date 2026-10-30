package com.mizhii.ledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mizhii.ledger.data.db.entity.RawCaptureEntity
import com.mizhii.ledger.data.db.entity.TxnSource
import kotlinx.coroutines.flow.Flow

@Dao
interface RawCaptureDao {

    @Insert
    suspend fun insert(item: RawCaptureEntity): Long

    @Query("SELECT * FROM raw_capture ORDER BY capturedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RawCaptureEntity>>

    /** 保留 7 天（§8.5） */
    @Query("DELETE FROM raw_capture WHERE capturedAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("SELECT COUNT(*) FROM raw_capture WHERE source = :source AND capturedAt >= :since")
    suspend fun countSince(source: TxnSource, since: Long): Int

    @Query("SELECT COUNT(*) FROM raw_capture")
    suspend fun count(): Int
}

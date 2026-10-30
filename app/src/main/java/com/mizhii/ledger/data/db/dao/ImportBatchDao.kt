package com.mizhii.ledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mizhii.ledger.data.db.entity.ImportBatchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportBatchDao {

    @Insert
    suspend fun insert(batch: ImportBatchEntity)

    /** 同一文件重复导入拒绝（§6.5.4） */
    @Query("SELECT * FROM import_batch WHERE fileSha256 = :sha256 LIMIT 1")
    suspend fun findBySha256(sha256: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batch ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<ImportBatchEntity>>

    // ── 备份 / 恢复（M5 §7.6）──────────────────────────────────────
    // 批次记录必须一起备份：否则换机恢复后再导入同一个账单文件，
    // 幂等校验失效，整份账单会被重复记一遍。

    @Query("SELECT * FROM import_batch ORDER BY importedAt ASC")
    suspend fun getAll(): List<ImportBatchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(items: List<ImportBatchEntity>)

    @Query("DELETE FROM import_batch")
    suspend fun deleteAll()
}

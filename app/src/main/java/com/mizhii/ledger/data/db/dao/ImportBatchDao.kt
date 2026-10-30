package com.mizhii.ledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
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
}

package com.mizhii.ledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mizhii.ledger.data.db.entity.IgnoreEntryEntity

@Dao
interface IgnoreEntryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: IgnoreEntryEntity)

    @Query("SELECT COUNT(*) FROM ignore_entry WHERE externalNo = :externalNo")
    suspend fun contains(externalNo: String): Int

    @Query("SELECT externalNo FROM ignore_entry")
    suspend fun allExternalNos(): List<String>

    // ── 备份 / 恢复（M5 §7.6）──────────────────────────────────────
    // 忽略名单丢了 = 用户删过的自动流水会在下次导入账单时全部复活。

    @Query("SELECT * FROM ignore_entry ORDER BY createdAt ASC")
    suspend fun getAll(): List<IgnoreEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(items: List<IgnoreEntryEntity>)

    @Query("DELETE FROM ignore_entry")
    suspend fun deleteAll()
}

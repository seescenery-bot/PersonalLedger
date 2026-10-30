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
}

package com.mizhii.ledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mizhii.ledger.data.db.entity.RuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<RuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RuleEntity)

    /** 分类时取用：按优先级升序，短路返回第一条命中（§6.7.3） */
    @Query("SELECT * FROM rule WHERE enabled = 1 ORDER BY priority ASC, createdAt ASC")
    suspend fun enabledRules(): List<RuleEntity>

    @Query("SELECT * FROM rule ORDER BY priority ASC, createdAt ASC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("DELETE FROM rule WHERE id = :id AND isBuiltin = 0")
    suspend fun deleteCustom(id: String)

    @Query("UPDATE rule SET hitCount = hitCount + 1 WHERE id = :id")
    suspend fun bumpHit(id: String)

    @Query("SELECT COUNT(*) FROM rule")
    suspend fun count(): Int
}

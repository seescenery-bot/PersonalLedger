package com.mizhii.ledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mizhii.ledger.data.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CategoryEntity)

    @Query("SELECT * FROM category WHERE isArchived = 0 ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE isArchived = 0 ORDER BY sortOrder ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int

    @Query("DELETE FROM category WHERE id = :id AND isBuiltin = 0")
    suspend fun deleteCustom(id: String)

    /** 删除自定义分类时，其下流水迁移到兜底分类（§7.4 删除保护） */
    @Query("UPDATE txn SET categoryId = :toCategoryId, categorySource = 'DEFAULT' WHERE categoryId = :fromCategoryId")
    suspend fun migrateTransactions(fromCategoryId: String, toCategoryId: String): Int
}

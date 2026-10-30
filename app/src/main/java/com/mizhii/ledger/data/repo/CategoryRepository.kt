package com.mizhii.ledger.data.repo

import com.mizhii.ledger.data.db.dao.CategoryDao
import com.mizhii.ledger.data.db.entity.CategoryEntity
import com.mizhii.ledger.data.db.entity.Direction
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class CategoryRepository(private val categoryDao: CategoryDao) {

    fun observeAll(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    suspend fun getAll(): List<CategoryEntity> = categoryDao.getAll()

    suspend fun upsert(category: CategoryEntity) = categoryDao.upsert(category)

    /** 新建自定义分类。最多二级，V1 只做一级（parentId 恒为 null）。 */
    suspend fun createCustom(
        name: String,
        direction: Direction,
        iconKey: String,
        colorHex: String
    ): CategoryEntity {
        val existing = categoryDao.getAll()
        val sortOrder = (existing.filter { it.direction == direction }.maxOfOrNull { it.sortOrder } ?: 0) + 1
        val category = CategoryEntity(
            id = "custom_${UUID.randomUUID()}",
            name = name.trim(),
            parentId = null,
            direction = direction,
            iconKey = iconKey,
            colorHex = colorHex,
            sortOrder = sortOrder,
            isBuiltin = false
        )
        categoryDao.upsert(category)
        return category
    }

    /**
     * 删除自定义分类（§7.4 删除保护）：先把该分类下的流水迁移到默认分类，再删分类。
     * 内置分类不可删除。返回被迁移的流水条数。
     */
    suspend fun deleteCustom(category: CategoryEntity): Int {
        if (category.isBuiltin) return 0
        val fallback = when (category.direction) {
            Direction.INCOME -> "other_income"
            Direction.EXPENSE -> "other_expense"
            null -> "other_expense"
        }
        val migrated = categoryDao.migrateTransactions(category.id, fallback)
        categoryDao.deleteCustom(category.id)
        return migrated
    }
}

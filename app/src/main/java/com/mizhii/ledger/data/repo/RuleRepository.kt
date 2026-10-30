package com.mizhii.ledger.data.repo

import com.mizhii.ledger.data.db.dao.RuleDao
import com.mizhii.ledger.data.db.entity.RuleEntity
import kotlinx.coroutines.flow.Flow

class RuleRepository(private val ruleDao: RuleDao) {

    fun observeAll(): Flow<List<RuleEntity>> = ruleDao.observeAll()

    suspend fun setEnabled(rule: RuleEntity, enabled: Boolean) =
        ruleDao.upsert(rule.copy(enabled = enabled))

    suspend fun deleteCustom(rule: RuleEntity) {
        if (!rule.isBuiltin) ruleDao.deleteCustom(rule.id)
    }
}

package com.mizhii.ledger.domain.capture

import com.mizhii.ledger.data.db.dao.RuleDao
import com.mizhii.ledger.data.db.entity.CategorySource
import com.mizhii.ledger.data.db.entity.MatchField
import com.mizhii.ledger.data.db.entity.MatchType
import com.mizhii.ledger.data.db.entity.RuleEntity
import com.mizhii.ledger.domain.model.TxnCandidate

/**
 * 自动分类规则引擎（需求文档 §6.7）。
 *
 * 优先级：**解析器已确定的性质（suggestedCategoryId）** > 用户规则（priority 1-999）
 * > 内置规则（5000+）> 方向兜底
 *
 * 为什么解析器建议优先于文本规则：解析器依据的是**确定性证据**——
 * 退款状态、支付宝账单自带的 `交易分类`（餐饮美食/日用百货…）、转账类型；
 * 而规则只是猜商户名。曾有商户名称里含「基金」二字，被内置规则误判成"投资"，
 * 就是文本猜测不可靠的例子。
 *
 * 匹配时**短路返回第一条命中**，不遍历全部。
 */
class Categorizer(private val ruleDao: RuleDao) {

    data class Result(
        val categoryId: String,
        val matchedRuleId: String?,
        val source: CategorySource
    )

    suspend fun categorize(candidate: TxnCandidate): Result {
        // ① 解析器给出的确定性质优先（退款 / 账单品类 / 往来款）
        candidate.suggestedCategoryId?.let {
            return Result(it, null, CategorySource.RULE)
        }
        // ② 文本规则
        val rules = ruleDao.enabledRules()
        for (rule in rules) {
            val text = fieldValue(candidate, rule.matchField) ?: continue
            if (matches(text, rule)) {
                runCatching { ruleDao.bumpHit(rule.id) }
                return Result(rule.categoryId, rule.id, CategorySource.RULE)
            }
        }
        // ③ 方向兜底
        val fallback = when (candidate.direction) {
            com.mizhii.ledger.data.db.entity.Direction.EXPENSE -> "other_expense"
            com.mizhii.ledger.data.db.entity.Direction.INCOME -> "other_income"
        }
        return Result(fallback, null, CategorySource.DEFAULT)
    }

    private fun fieldValue(candidate: TxnCandidate, field: MatchField): String? = when (field) {
        MatchField.COUNTERPARTY -> candidate.counterparty
        MatchField.GOODS -> candidate.goods
        MatchField.RAW_TEXT -> candidate.rawText
        // 渠道规则暂不支持（没有对应的可匹配文本）
        MatchField.CHANNEL -> null
    }

    private fun matches(text: String, rule: RuleEntity): Boolean {
        val pattern = rule.pattern.trim()
        if (pattern.isEmpty()) return false
        return when (rule.matchType) {
            MatchType.CONTAINS -> text.contains(pattern, ignoreCase = true)
            MatchType.STARTS_WITH -> text.startsWith(pattern, ignoreCase = true)
            MatchType.EQUALS -> text.equals(pattern, ignoreCase = true)
            MatchType.REGEX -> runCatching {
                Regex(rule.pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
            }.getOrDefault(false)
        }
    }
}

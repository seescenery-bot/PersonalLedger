package com.mizhii.ledger.domain.capture

import com.mizhii.ledger.data.db.entity.TxnSource
import com.mizhii.ledger.domain.model.TxnCandidate

/**
 * 置信度评分（需求文档 §6.6.1）。
 *
 * 口径已按真机样本校准：一条真实的微信支付通知
 * （title=微信支付 / text=[2条]微信支付: 已支付¥0.01）应当得到 60 分落进 PENDING，
 * 而不是被 40 分门槛丢弃。
 */
object ConfidenceScorer {

    /** 各来源基础分 */
    private fun sourceBase(source: TxnSource): Int = when (source) {
        TxnSource.CSV -> 50
        TxnSource.ACCESSIBILITY -> 25
        TxnSource.NOTIFICATION -> 20
        TxnSource.MERGED -> 30
        TxnSource.MANUAL -> 100
    }

    /**
     * @param hasClearSemantic 是否命中明确的支出/收入/退款语义
     * @param hasAmount        金额是否解析成功（进了这里必然是 true，保留参数以便扩展）
     * @param hasRuleMatch     是否命中分类规则
     * @param merged           是否由多个来源合并而来
     */
    fun score(
        candidate: TxnCandidate,
        hasClearSemantic: Boolean,
        hasAmount: Boolean = true,
        hasRuleMatch: Boolean = false,
        merged: Boolean = false
    ): Int {
        var score = sourceBase(candidate.source)
        if (hasClearSemantic) score += 25
        if (hasAmount) score += 15
        if (!candidate.externalNo.isNullOrBlank()) score += 20
        if (!candidate.counterparty.isNullOrBlank()) score += 15
        if (hasRuleMatch) score += 10
        if (merged) score += 10
        return score.coerceIn(0, 100)
    }

    /** 是否直接入账（>= 阈值）。由调用方传入用户可配置的阈值。 */
    fun isConfirmed(score: Int, threshold: Int): Boolean = score >= threshold

    /**
     * 分数 → 状态。
     * - >= 阈值：CONFIRMED
     * - 40..阈值-1：PENDING（待确认）
     * - < 40：丢弃
     */
    fun shouldDiscard(score: Int): Boolean = score < 40
}

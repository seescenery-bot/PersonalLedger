package com.mizhii.ledger.domain.model

import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.TimePrecision
import com.mizhii.ledger.data.db.entity.TxnSource

/**
 * 归一化后的候选流水（需求文档 §6.5.1）。
 * 这是"还没落库的一笔账"，管道里各环节都围绕它工作。
 *
 * amountCents 单位是「分」，且恒 > 0；方向由 direction 表达。
 */
data class TxnCandidate(
    val channel: Channel,
    val direction: Direction,
    val amountCents: Long,
    val occurredAt: Long,
    val timePrecision: TimePrecision = TimePrecision.INSTANT,
    val counterparty: String? = null,
    val goods: String? = null,
    val externalNo: String? = null,
    val merchantNo: String? = null,
    val payMethod: String? = null,
    val rawText: String? = null,
    val source: TxnSource,
    val confidence: Int = 0,
    /** 来源通知/页面的标题，用于诊断 */
    val originTitle: String? = null,
    /** 解析器直接给出的建议分类（如退款 → refund、转账 → transfer_in），可为空 */
    val suggestedCategoryId: String? = null,
    /**
     * 强制进待确认。
     * 用于「拿不到付款方的收入」：收款码到账、红包、无源头的转账等。
     * 这类钱很可能是朋友还钱/套现而非真实收入，自动计入会污染收入统计。
     */
    val forcePending: Boolean = false
)

/** 丢弃原因，写入 raw_capture.discardReason，便于诊断为什么没记上 */
object DiscardReason {
    const val NOT_PAYMENT_TITLE = "NOT_PAYMENT_TITLE"
    const val EXCLUDED = "EXCLUDED"
    const val NO_SEMANTIC = "NO_SEMANTIC"
    const val NO_AMOUNT = "NO_AMOUNT"
    const val TOO_SMALL = "TOO_SMALL"
    const val DUPLICATE = "DUPLICATE"
    const val IGNORED = "IGNORED"
    const val EXCEPTION = "EXCEPTION"
    const val LOW_CONFIDENCE = "LOW_CONFIDENCE"
}

/** 解析结果 */
sealed interface ParseOutcome {
    data class Success(val candidate: TxnCandidate) : ParseOutcome
    data class Discarded(val reason: String, val normalizedText: String) : ParseOutcome
}

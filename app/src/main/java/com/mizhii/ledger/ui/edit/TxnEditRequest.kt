package com.mizhii.ledger.ui.edit

import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.repo.TxnInput

/**
 * 记账/编辑 Sheet 提交的请求。id 为 null 表示新增。
 * 用请求对象而不是直接传实体，避免 UI 层构造非法 TxnEntity（如忘记设置 rowHash）。
 */
data class TxnEditRequest(
    val id: Long? = null,
    val amountCents: Long,
    val direction: Direction,
    val channel: Channel,
    val categoryId: String?,
    val occurredAt: Long,
    val counterparty: String? = null,
    val note: String? = null
) {
    fun toInput(): TxnInput = TxnInput(
        amountCents = amountCents,
        direction = direction,
        channel = channel,
        categoryId = categoryId,
        occurredAt = occurredAt,
        counterparty = counterparty,
        note = note
    )
}

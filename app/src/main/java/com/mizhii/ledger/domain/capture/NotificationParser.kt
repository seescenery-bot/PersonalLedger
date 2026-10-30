package com.mizhii.ledger.domain.capture

import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.TxnSource
import com.mizhii.ledger.domain.model.DiscardReason
import com.mizhii.ledger.domain.model.ParseOutcome
import com.mizhii.ledger.domain.model.TxnCandidate
import com.mizhii.ledger.util.Money
import com.mizhii.ledger.util.Regexes

/**
 * 通知文本解析器（通道 A，需求文档 §6.2）。
 *
 * 判定顺序是硬约束（§6.2.4 的真机反例为证）：
 *   1. title 白名单
 *   2. 排除词        ← 必须在抽金额之前！支付宝还款提醒里全是金额
 *   3. 退款 / 支出 / 收入 语义
 *   4. 才抽金额
 *
 * 纯函数、无 Android 依赖，因此可被单元测试完整覆盖。
 */
object NotificationParser {

    /** 包名 → 渠道 */
    fun channelOf(packageName: String): Channel? = when (packageName) {
        "com.tencent.mm" -> Channel.WECHAT
        "com.eg.android.AlipayGphone" -> Channel.ALIPAY
        else -> null
    }

    /** title 是否属于支付类通知（§6.2.2） */
    fun isPaymentTitle(packageName: String, title: String?): Boolean {
        val t = title?.trim().orEmpty()
        return when (packageName) {
            // 微信：真机样本证实 title 稳定为「微信支付」，可以严格白名单
            "com.tencent.mm" -> Regexes.WECHAT_PAY_TITLES.any { it == t }
            // 支付宝：**不按 title 收紧**。
            // 实测其还款/账单提醒的 title 五花八门（"信用贷自动还款失败"、"本月花呗账单已还清最低"…），
            // 而真实的「支付成功」通知 title 尚未采到样本。贸然白名单会漏记真实交易，
            // 噪音交给排除词（§6.2.4）处理即可——反正解析是纯内存开销。
            "com.eg.android.AlipayGphone" -> true
            else -> false
        }
    }

    /**
     * 归一化（§6.2.3 第二步）。
     * `[2条]微信支付: 已支付¥0.01` → `已支付¥0.01`
     */
    fun normalize(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return s
        var out = s.replace(Regexes.AGGREGATE_PREFIX, "")
        out = out.replace(Regexes.ACCOUNT_PREFIX, "")
        return out.trim()
    }

    /** 抽取金额（分）。按 符号 → 元 → 锚点 → 裸小数 四级回退。 */
    fun extractAmountCents(text: String): Long? {
        for (pattern in Regexes.AMOUNT_PATTERNS) {
            val m = pattern.find(text) ?: continue
            val cents = Money.parseYuanToCents(m.groupValues[1]) ?: continue
            if (cents > 0) return cents
        }
        return null
    }

    /** 抽取商户/对方（通道 A 通常抽不到，通道 B/C 才有） */
    fun extractCounterparty(text: String): String? {
        for (pattern in Regexes.MERCHANT_PATTERNS) {
            val m = pattern.find(text) ?: continue
            val value = m.groupValues[1].trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    fun extractExternalNo(text: String): String? {
        val m = Regexes.EXTERNAL_NO.find(text) ?: return null
        return m.groupValues[1].takeIf { it.isNotBlank() }
    }

    /**
     * 解析一条通知。
     *
     * @param packageName 通知来源包名
     * @param title       EXTRA_TITLE
     * @param body        EXTRA_TEXT（或 BIG_TEXT / TEXT_LINES 合并后的文本）
     * @param postTime    sbn.postTime —— 不要用当前时间，否则账期会错
     */
    fun parse(
        packageName: String,
        title: String?,
        body: String?,
        postTime: Long,
        acceptAnyPackage: Boolean = false
    ): ParseOutcome {
        val channel = channelOf(packageName) ?: if (acceptAnyPackage) Channel.OTHER else null
        if (channel == null) {
            return ParseOutcome.Discarded(DiscardReason.NOT_PAYMENT_TITLE, body.orEmpty())
        }

        val cleanTitle = title?.trim().orEmpty()
        // 调试模式下允许任意包名，但标题仍要求命中支付语义或非微信/支付宝的调试标记
        if (!acceptAnyPackage && !isPaymentTitle(packageName, cleanTitle)) {
            return ParseOutcome.Discarded(DiscardReason.NOT_PAYMENT_TITLE, body.orEmpty())
        }

        val normalized = normalize(body)
        val combined = if (cleanTitle.isEmpty()) normalized else "$cleanTitle $normalized"

        // ① 排除词优先——真机反例：支付宝还款提醒带金额
        if (Regexes.EXCLUDE_KEYWORDS.any { combined.contains(it) }) {
            return ParseOutcome.Discarded(DiscardReason.EXCLUDED, normalized)
        }

        // ② 语义判定（退款优先于收入，因为"退款到账"同时含收入词）
        val isRefund = Regexes.REFUND_KEYWORDS.any { combined.contains(it) }
        val direction: Direction = when {
            isRefund -> Direction.INCOME
            Regexes.EXPENSE_KEYWORDS.any { combined.contains(it) } -> Direction.EXPENSE
            Regexes.INCOME_KEYWORDS.any { combined.contains(it) } -> Direction.INCOME
            else -> return ParseOutcome.Discarded(DiscardReason.NO_SEMANTIC, normalized)
        }

        // ③ 最后才抽金额
        val amountCents = extractAmountCents(normalized)
            ?: return ParseOutcome.Discarded(DiscardReason.NO_AMOUNT, normalized)

        // ④ 收入再细分性质：退款 / 往来款转账 / 收款码收款
        val counterparty = extractCounterparty(normalized)
        val isTransferIn = direction == Direction.INCOME &&
            Regexes.TRANSFER_IN_KEYWORDS.any { combined.contains(it) }
        val isCollect = direction == Direction.INCOME &&
            Regexes.COLLECT_KEYWORDS.any { combined.contains(it) }

        val suggestedCategoryId = when {
            isRefund -> "refund"
            isTransferIn -> "transfer_in"
            else -> null
        }

        // ⑤ 收入如果连付款方都拿不到，一律强制人工裁决
        //    （收款码到账、红包、无源转账都属此类：可能是还钱/套现，不是真实收入）
        val forcePending = direction == Direction.INCOME && !isRefund && counterparty == null

        val candidate = TxnCandidate(
            channel = channel,
            direction = direction,
            amountCents = amountCents,
            occurredAt = postTime,
            counterparty = counterparty,
            goods = null,
            externalNo = extractExternalNo(normalized),
            rawText = normalized,
            source = TxnSource.NOTIFICATION,
            originTitle = cleanTitle.ifEmpty { null },
            suggestedCategoryId = suggestedCategoryId,
            forcePending = forcePending
        )
        return ParseOutcome.Success(candidate)
    }
}

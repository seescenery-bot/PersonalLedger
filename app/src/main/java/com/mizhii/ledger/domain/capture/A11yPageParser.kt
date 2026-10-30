package com.mizhii.ledger.domain.capture

import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.TxnSource
import com.mizhii.ledger.domain.model.DiscardReason
import com.mizhii.ledger.domain.model.ParseOutcome
import com.mizhii.ledger.domain.model.TxnCandidate
import com.mizhii.ledger.util.Money
import com.mizhii.ledger.util.Regexes
import java.time.LocalDateTime
import java.time.ZoneId

/** 无障碍读屏的输入：当前窗口里按遍历顺序拿到的纯文本列表 */
data class A11yPageInput(
    val packageName: String,
    val texts: List<String>,
    val postTime: Long
)

/**
 * 通道 B：无障碍页面解析（需求文档 §6.3.3）。
 *
 * 设计要点：
 *  - 只吃 `List<String>`，不依赖 View 层级与 resource-id —— 微信/支付宝会混淆资源名，
 *    任何"按 id 找控件"的写法在版本更新后必崩。层级信息只在采集侧（Service）用于限流。
 *  - 全部是启发式，**按优先级尝试、失败跳过**，不假设页面结构。
 *  - 纯函数，可单测。
 *
 * ── 真机校准结论（2026-10-29，157 个真实页面全量回归）────────────────────
 *
 * 校准前的实现对真机页面误报率极高：157 个页面里 11 个"解析成功"，其中 7 个是假的。
 * 典型案例：微信首页被解析成一笔 ¥23.00 的收入，因为第 59 条文本里有聊天预览
 * `[转账] 已收款`；另一页把「鸣潮3.4版本获奖了」里的 3.4 当成 ¥3.40。
 *
 * 两条规则治好了全部 7 个误报：
 *
 *  1. **成功标记必须出现在页面顶部**（前 5 条文本、且文本长度 ≤ 10）。
 *     真机两个支付成功页：微信 `texts[0]="支付成功"`、支付宝 `texts[1]="支付成功"`；
 *     而误报页的标记分别在第 59 条和第 21 条。支付成功页永远是"标题 + 商户 + 金额"，很短。
 *
 *  2. **金额必须"像金额"**（见 [extractAmount]）：只认 币种符号 / 「元」结尾 / 「金额：」标签，
 *     外加"整段就是一个数"的节点。裸小数一律不认 —— 页面是一整屏文字，裸小数全是版本号、
 *     积分、步数。
 *
 * 仍然存在的局限（写在这里，别当成 bug）：
 *  - 微信支付成功页的商户是个人昵称（如 `示例商户（**例）`），可能带手机号；
 *  - 支付成功页与通知页同时到达时靠 [Deduper.merge] 互补（通知给金额、页面给商户）；
 *  - 页面切换过程中可能抓到"上一页 + 下一页"叠在一起的中间态（实测支付宝 131 号页面），
 *    靠规则 1 的顶部窗口天然排除。
 */
object A11yPageParser {

    /** 成功标记必须出现在页面顶部的前 N 条文本里 */
    private const val MARKER_WINDOW = 5

    /** 金额必须出现在前 N 条文本里（真机最靠后的是微信非税支付回执，在第 10 条） */
    private const val AMOUNT_WINDOW = 15

    /** 标记文本的最大长度。标题式 "支付成功"(4) / "收款成功通知"(6)；聊天预览通常更长 */
    private const val MARKER_MAX_LEN = 10

    /** 出现任一标记才认为当前是"交易成功"页（支出页与收款页都算） */
    private val SUCCESS_MARKERS = listOf(
        "支付成功", "付款成功", "交易成功", "转账成功", "支付完成", "付款完成", "已支付",
        "收款成功", "已收款", "收款到账", "到账成功"
    )

    /** 明确的"付出去"证据。优先级最高：真机非税支付回执同时含「收款成功通知」和「支付金额：」 */
    private val HARD_EXPENSE = listOf("支付金额", "实付金额", "付款金额", "交易金额")

    private val EXPENSE_MARKERS = listOf(
        "支付成功", "付款成功", "交易成功", "转账成功", "支付完成", "付款完成",
        "已支付", "扣款成功", "付款给", "已付款"
    )

    private val INCOME_MARKERS = listOf(
        "收款成功", "已收款", "收款到账", "到账成功", "已到账", "收款金额", "转账到账"
    )

    /** 收款侧证据，用来识别"双向证据冲突"（见 forcePending） */
    private val COLLECT_MARKERS = listOf("收款成功", "已收款", "收款到账", "到账成功", "收款金额")

    /**
     * 余额类文本不能当金额。这是最容易被忽略的坑：
     * 支付成功页上通常还显示"零钱余额 ¥1,234.56"，取错就是一笔巨款错账。
     */
    private val BALANCE_GUARD = listOf(
        "余额", "可用", "总金额", "总资产", "资产", "零钱", "本月", "累计", "额度", "限额"
    )

    private val MERCHANT_LABELS = listOf(
        "收款方", "商户全称", "商户名称", "收款商户", "对方", "服务提供", "支付给"
    )

    private val EXTERNAL_NO_LABELS = listOf("交易单号", "商户单号", "交易订单号", "订单号")

    /** 按钮 / 导航 / 页面标签 —— 它们经常紧挨着金额，但不是商户名 */
    private val BUTTON_WORDS = listOf(
        "完成", "回首页", "返回", "确定", "关闭", "详情", "查看更多", "立即领取", "复制",
        "投诉", "更多", "去支付", "确认支付", "收付款", "支付有礼", "大众会员",
        "扫一扫", "搜索", "取消", "重试", "我知道了", "付款方式", "交易详情", "支付时间"
    )

    private val DATETIME = Regex("""(\d{4})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2})(?::(\d{2}))?""")

    private val NOISE = listOf(
        "单号", "订单", "时间", "方式", "备注", "支付成功", "付款成功", "交易成功",
        "收款成功", "余额", "金额"
    )

    fun parse(input: A11yPageInput): ParseOutcome {
        val channel = NotificationParser.channelOf(input.packageName)
            ?: return ParseOutcome.Discarded(DiscardReason.NOT_PAYMENT_TITLE, "")

        val texts = input.texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) {
            return ParseOutcome.Discarded(DiscardReason.NO_SEMANTIC, "")
        }
        val joined = texts.joinToString(" | ").take(1000)

        // ① 成功标记必须出现在页面顶部（真机校准的核心规则，见类注释）
        val markerText = texts.take(MARKER_WINDOW).firstOrNull { text ->
            text.length <= MARKER_MAX_LEN && SUCCESS_MARKERS.any { text.contains(it) }
        } ?: return ParseOutcome.Discarded(DiscardReason.NO_SEMANTIC, joined)
        val marker = SUCCESS_MARKERS.first { markerText.contains(it) }

        // ② 方向：先"硬支出证据"，再支出标记，最后收入标记
        val hasHardExpense = HARD_EXPENSE.any { joined.contains(it) }
        val isExpense = hasHardExpense || EXPENSE_MARKERS.any { joined.contains(it) }
        val isIncome = INCOME_MARKERS.any { joined.contains(it) }
        val direction = when {
            isExpense -> Direction.EXPENSE
            isIncome -> Direction.INCOME
            else -> return ParseOutcome.Discarded(DiscardReason.NO_SEMANTIC, joined)
        }

        // ③ 金额（强匹配优先，余额保护）
        val amountResult = extractAmount(texts)
            ?: return ParseOutcome.Discarded(DiscardReason.NO_AMOUNT, joined)

        val counterparty = extractMerchant(texts, amountResult.second)

        // ④ 双向证据冲突 → 强制人工裁决。
        //    真机案例：微信「非税支付」回执同时有"收款成功通知"（收款方视角）和
        //    "支付金额：20.00元"（付款方视角），实际是用户付出去 20 元。
        val conflicting = isExpense && COLLECT_MARKERS.any { joined.contains(it) }
        val incomeWithoutPayer = direction == Direction.INCOME && counterparty == null

        val candidate = TxnCandidate(
            channel = channel,
            direction = direction,
            amountCents = amountResult.first,
            occurredAt = extractTime(texts) ?: input.postTime,
            counterparty = counterparty,
            externalNo = extractExternalNo(texts),
            rawText = joined,
            source = TxnSource.ACCESSIBILITY,
            originTitle = marker,
            forcePending = conflicting || incomeWithoutPayer
        )
        return ParseOutcome.Success(candidate)
    }

    /**
     * 抽金额。分两轮：
     *  1. **强匹配**：币种符号 / 「元」结尾 / 「金额：」标签 —— 扫完整个窗口，第一个命中即可；
     *  2. **弱匹配**：整段文本就是一个数字（真机里金额常与 ¥ 拆成两个节点）。
     *
     * 强匹配优先于"位置更靠前的弱匹配"，避免页面上方有评分 / 版本号时取错。
     *
     * @return (分, 该金额所在文本的下标)
     */
    internal fun extractAmount(texts: List<String>): Pair<Long, Int>? {
        val window = texts.take(AMOUNT_WINDOW)

        for ((index, text) in window.withIndex()) {
            if (BALANCE_GUARD.any { text.contains(it) }) continue
            for (pattern in Regexes.AMOUNT_PATTERNS_STRICT) {
                val match = pattern.find(text) ?: continue
                val cents = Money.parseYuanToCents(match.groupValues[1]) ?: continue
                if (cents > 0) return cents to index
            }
        }

        var weak: Pair<Long, Int>? = null
        for ((index, text) in window.withIndex()) {
            if (BALANCE_GUARD.any { text.contains(it) }) continue
            val match = Regexes.AMOUNT_ONLY_NUMBER.find(text) ?: continue
            val cents = Money.parseYuanToCents(match.groupValues[1]) ?: continue
            if (cents > 0 && weak == null) weak = cents to index
        }
        return weak
    }

    internal fun extractMerchant(texts: List<String>, amountIndex: Int): String? {
        // 1) 同一节点内："收款方 星巴克咖啡"
        for (text in texts) {
            for (label in MERCHANT_LABELS) {
                val idx = text.indexOf(label)
                if (idx < 0) continue
                val rest = text.substring(idx + label.length).trim().trimStart(':', '：').trim()
                if (isValidName(rest)) return rest
            }
        }
        // 2) 标签独立成节点，取紧随其后的节点（真机：服务提供： / 广东财政）
        for ((i, text) in texts.withIndex()) {
            val normalized = text.trim().trimEnd(':', '：').trim()
            if (MERCHANT_LABELS.any { it == normalized }) {
                texts.getOrNull(i + 1)?.takeIf { isValidName(it) }?.let { return it }
            }
        }
        // 3) 金额**前**一个节点 —— 微信支付成功页布局是 [标题, 商户, 金额, 按钮]
        texts.getOrNull(amountIndex - 1)?.takeIf { isValidName(it) }?.let { return it }
        // 4) 金额后第一个像名字的节点 —— 支付宝支付成功页布局是 [标题, 金额, 商户, 原价, ...]
        for (i in (amountIndex + 1) until texts.size) {
            if (isValidName(texts[i])) return texts[i]
        }
        return null
    }

    internal fun extractExternalNo(texts: List<String>): String? {
        for (text in texts) {
            for (label in EXTERNAL_NO_LABELS) {
                val idx = text.indexOf(label)
                if (idx < 0) continue
                val rest = text.substring(idx + label.length).trim().trimStart(':', '：').trim()
                Regex("[A-Za-z0-9]{8,40}").find(rest)?.let { return it.value }
            }
        }
        for ((i, text) in texts.withIndex()) {
            val normalized = text.trim().trimEnd(':', '：').trim()
            if (EXTERNAL_NO_LABELS.any { it == normalized }) {
                val next = texts.getOrNull(i + 1) ?: continue
                Regex("[A-Za-z0-9]{8,40}").find(next)?.let { return it.value }
            }
        }
        return null
    }

    internal fun extractTime(texts: List<String>): Long? {
        for (text in texts) {
            val m = DATETIME.find(text) ?: continue
            val g = m.groupValues
            val parsed = runCatching {
                val second = if (g[6].isBlank()) 0 else g[6].toInt()
                LocalDateTime.of(
                    g[1].toInt(), g[2].toInt(), g[3].toInt(),
                    g[4].toInt(), g[5].toInt(), second
                ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            if (parsed.isSuccess) return parsed.getOrNull()
        }
        return null
    }

    private fun isValidName(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 30) return false
        if (t.all { it.isDigit() }) return false
        if (t.contains("¥") || t.contains("￥")) return false
        if (DATETIME.containsMatchIn(t)) return false
        if (MERCHANT_LABELS.any { it == t.trimEnd(':', '：') }) return false
        if (BUTTON_WORDS.any { it == t }) return false
        if (NOISE.any { t.contains(it) }) return false
        return true
    }
}

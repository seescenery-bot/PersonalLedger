package com.mizhii.ledger.widget

import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.util.Dates
import com.mizhii.ledger.util.Money

/**
 * 小部件要显示的一份快照（M6）。
 *
 * 纯数据 + 纯函数，**不碰 Android API**，这样文案与格式可以单测。
 * 小部件本身只是把这些字符串塞进 RemoteViews。
 */
data class WidgetSnapshot(
    val monthKey: String,
    val expenseCents: Long,
    val incomeCents: Long,
    val txnCount: Int,
    val recent: List<WidgetRecent>,
) {
    val hasData: Boolean get() = txnCount > 0
    val balanceCents: Long get() = incomeCents - expenseCents
}

data class WidgetRecent(
    val id: Long,
    val counterparty: String?,
    val amountCents: Long,
    val direction: Direction,
    val dayKey: String,
)

/** 小部件文案与格式化（HyperOS 小部件设计规范的「信息提炼」部分） */
object WidgetText {

    const val MAX_RECENT_WIDE = 3
    const val MAX_RECENT_TALL = 5

    /** 「10月」——月份是主标题，年份放不下也不重要 */
    fun monthLabel(monthKey: String): String = "${Dates.monthOf(monthKey)}月"

    /** 规范：日期统一用「月–日」 */
    fun dayLabel(dayKey: String): String {
        val parts = dayKey.split('-')
        if (parts.size != 3) return dayKey
        return "${parts[1].toIntOrNull() ?: parts[1]}–${parts[2].toIntOrNull() ?: parts[2]}"
    }

    fun expenseText(snapshot: WidgetSnapshot): String =
        Money.formatWithSymbol(snapshot.expenseCents)

    fun incomeText(snapshot: WidgetSnapshot): String =
        Money.formatWithSymbol(snapshot.incomeCents)

    fun balanceText(snapshot: WidgetSnapshot): String =
        Money.formatWithSymbol(snapshot.balanceCents)

    /** 收入/支出金额前面带正负号，颜色再由调用方按方向决定 */
    fun signedAmount(item: WidgetRecent): String {
        val sign = if (item.direction == Direction.EXPENSE) "-" else "+"
        return sign + Money.formatWithSymbol(item.amountCents)
    }

    /** 最近一笔的主标题：没有商户名时退化成「未命名」而不是空白 */
    fun recentTitle(item: WidgetRecent): String =
        item.counterparty?.takeIf { it.isNotBlank() } ?: "未填写对方"

    /** 最近一笔的副标题：「10–29 · 餐饮」这种信息太挤，只留日期 */
    fun recentSubtitle(item: WidgetRecent): String = dayLabel(item.dayKey)

    /** 无内容场景（规范要求必须设计） */
    fun emptyTitle(): String = "本月还没有流水"

    fun emptyHint(): String = "打开简账，微信支付宝的收支会自动记进来"

    /** 底部统计行 */
    fun footer(snapshot: WidgetSnapshot): String = "本月 ${snapshot.txnCount} 笔"
}

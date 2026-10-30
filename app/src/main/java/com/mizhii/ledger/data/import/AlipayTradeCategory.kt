package com.mizhii.ledger.data.import

import com.mizhii.ledger.data.db.entity.Direction

/**
 * 支付宝账单「交易分类」→ 本应用分类 id 的映射。
 *
 * 为什么值得单独做：支付宝的 `交易分类` 是**真实消费品类**（餐饮美食 / 日用百货 / 医疗健康 …），
 * 而微信账单的 `交易类型` 只是动作类型（商户消费 / 扫二维码付款 / 零钱提现），对分类没有信息量。
 * 真机 256 笔支付宝账单里，`交易分类` 能覆盖 **182/185 笔支出（98%）**。
 *
 * 空值规则：
 *  - `信用借还` / `账户存取` → **不给分类**：这些是花呗借呗还款、余额宝存取，
 *    在账单里本来就是「不计收支」，根本不会入账；万一日后按收支出现，也应由方向兜底而不是猜品类。
 *  - 未来新增的未知品类 → 返回 null，落回规则引擎 / 方向兜底，不会误分类。
 */
object AlipayTradeCategory {

    /** 方向无关的支出品类 */
    private val EXPENSE: Map<String, String> = mapOf(
        // 真机账单实测出现过的
        "餐饮美食" to "food",
        "日用百货" to "daily",
        "医疗健康" to "medical",
        "文化休闲" to "entertainment",
        "商业服务" to "other_expense",
        "公共服务" to "other_expense",
        "爱车养车" to "transport",
        "充值缴费" to "communication",
        "教育培训" to "education",
        // 支付宝常见但本次账单没出现的（预先覆盖，避免以后再改代码）
        "交通出行" to "transport",
        "服饰装扮" to "shopping",
        "数码电器" to "shopping",
        "美容美发" to "shopping",
        "家居生活" to "daily",
        "酒店旅游" to "entertainment",
        "运动户外" to "fitness",
        "母婴亲子" to "daily",
        "宠物" to "daily",
        "快递物流" to "daily",
        "生活服务" to "other_expense",
        "投资理财" to "invest",
        "保险" to "other_expense",
        "公益捐赠" to "other_expense",
        "转账红包" to "transfer_out",
    )

    /** 收入侧的品类 */
    private val INCOME: Map<String, String> = mapOf(
        "转账红包" to "transfer_in",
        "投资理财" to "invest_income",
        "退款" to "refund",
        "红包" to "redpacket",
    )

    /**
     * @return 建议分类 id；无法判定时返回 null（交给规则引擎 / 方向兜底）
     */
    fun categoryOf(tradeCategory: String?, direction: Direction?): String? {
        val key = tradeCategory?.trim().orEmpty()
        if (key.isEmpty()) return null
        return when (direction) {
            Direction.EXPENSE -> EXPENSE[key]
            Direction.INCOME -> INCOME[key]
            null -> null
        }
    }
}

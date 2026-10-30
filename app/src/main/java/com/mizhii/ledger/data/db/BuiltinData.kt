package com.mizhii.ledger.data.db

import com.mizhii.ledger.data.db.entity.CategoryEntity
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.MatchField
import com.mizhii.ledger.data.db.entity.MatchType
import com.mizhii.ledger.data.db.entity.RuleEntity

/**
 * 内置分类与内置规则（文档 附录 B）。
 * 首次启动时写入数据库；内置规则不可删除，但可禁用。
 */
object BuiltinData {

    private fun cat(
        id: String,
        name: String,
        iconKey: String,
        colorHex: String,
        sortOrder: Int,
        direction: Direction
    ) = CategoryEntity(
        id = id,
        name = name,
        parentId = null,
        direction = direction,
        iconKey = iconKey,
        colorHex = colorHex,
        sortOrder = sortOrder,
        isBuiltin = true
    )

    val categories: List<CategoryEntity> = listOf(
        // ---- 支出 ----
        cat("food", "餐饮", "restaurant", "#EE7B4D", 10, Direction.EXPENSE),
        cat("transport", "交通", "directions_bus", "#4A90D9", 20, Direction.EXPENSE),
        cat("shopping", "购物", "shopping_bag", "#C060A1", 30, Direction.EXPENSE),
        cat("daily", "日用", "shopping_cart", "#7E9B3F", 40, Direction.EXPENSE),
        cat("housing", "居住", "home", "#8D6E63", 50, Direction.EXPENSE),
        cat("communication", "通讯", "phone", "#3F8F8F", 60, Direction.EXPENSE),
        cat("entertainment", "娱乐", "sports_esports", "#9B6BD6", 70, Direction.EXPENSE),
        cat("medical", "医疗", "local_hospital", "#D9534F", 80, Direction.EXPENSE),
        cat("education", "教育", "school", "#5B8DEF", 90, Direction.EXPENSE),
        cat("fitness", "运动", "fitness_center", "#2E9E63", 100, Direction.EXPENSE),
        cat("invest", "投资", "trending_up", "#B8860B", 110, Direction.EXPENSE),
        cat("transfer_out", "转账支出", "swap_horiz", "#78909C", 120, Direction.EXPENSE),
        cat("fee", "手续费", "receipt_long", "#9E9E9E", 130, Direction.EXPENSE),
        cat("other_expense", "其他支出", "more_horiz", "#909499", 900, Direction.EXPENSE),

        // ---- 收入 ----
        cat("salary", "工资", "payments", "#2E7D6B", 210, Direction.INCOME),
        cat("refund", "退款", "undo", "#4C9A7A", 220, Direction.INCOME),
        cat("bonus", "奖金", "emoji_events", "#C9A227", 230, Direction.INCOME),
        cat("redpacket", "红包", "redeem", "#D9534F", 240, Direction.INCOME),
        cat("transfer_in", "转账收入", "swap_horiz", "#4A90D9", 250, Direction.INCOME),
        cat("invest_income", "投资收益", "trending_up", "#B8860B", 260, Direction.INCOME),
        cat("other_income", "其他收入", "more_horiz", "#909499", 910, Direction.INCOME)
    )

    /** 关键词 → 分类。内置规则优先级固定 5000（§6.7.1 / 附录 B.3） */
    private val keywords: Map<String, List<String>> = mapOf(
        "food" to listOf(
            "美团", "饿了么", "肯德基", "麦当劳", "星巴克", "瑞幸", "蜜雪", "海底捞",
            "喜茶", "奈雪", "沙县", "老乡鸡", "必胜客", "汉堡王", "便利店", "食堂", "外卖", "餐厅", "咖啡"
        ),
        "transport" to listOf(
            "滴滴", "高德", "曹操", "T3", "地铁", "公交", "12306", "铁路", "航空",
            "加油", "停车", "高速", "哈啰", "青桔", "美团单车", "充电桩"
        ),
        "shopping" to listOf(
            "淘宝", "天猫", "京东", "拼多多", "唯品会", "苏宁", "抖音商城", "小米商城",
            "超市", "沃尔玛", "永辉", "便利蜂", "名创优品", "优衣库"
        ),
        "daily" to listOf("纸巾", "洗衣液", "日用", "五金", "家居"),
        "housing" to listOf("房租", "物业", "水费", "电费", "燃气", "暖气", "宽带", "中介"),
        "communication" to listOf("中国移动", "中国联通", "中国电信", "话费", "流量"),
        "entertainment" to listOf(
            "电影", "影院", "猫眼", "淘票票", "游戏", "Steam", "网易", "腾讯视频",
            "爱奇艺", "优酷", "B站", "哔哩哔哩", "KTV", "酒吧", "演出", "门票", "会员"
        ),
        "medical" to listOf("医院", "药房", "药店", "诊所", "挂号", "体检", "口腔", "眼科"),
        "education" to listOf("学费", "培训", "书店", "当当", "课程", "考试", "报名"),
        "fitness" to listOf("健身", "游泳", "球馆", "瑜伽", "Keep", "体育"),
        "invest" to listOf("基金", "股票", "理财", "保险", "定投"),
        "transfer_out" to listOf("转账", "红包", "亲属卡", "AA"),
        "fee" to listOf("手续费", "服务费", "利息"),
        "salary" to listOf("工资", "薪资", "代发", "劳务"),
        "refund" to listOf("退款", "退货", "已退款"),
        "bonus" to listOf("奖金", "绩效", "年终"),
        "redpacket" to listOf("红包", "压岁钱"),
        "transfer_in" to listOf("收款", "转账", "收到"),
        "invest_income" to listOf("收益", "分红", "利息", "余额宝")
    )

    val rules: List<RuleEntity> = keywords.flatMap { (categoryId, words) ->
        words.mapIndexed { index, word ->
            RuleEntity(
                id = "builtin_${categoryId}_$index",
                priority = 5000,
                matchField = MatchField.COUNTERPARTY,
                matchType = MatchType.CONTAINS,
                pattern = word,
                categoryId = categoryId,
                enabled = true,
                isBuiltin = true
            )
        }
    }
}

package com.mizhii.ledger.data.db.entity

/** 收支方向 */
enum class Direction { EXPENSE, INCOME }

/** 资金渠道（不是"账户余额"概念，见文档 §2） */
enum class Channel { WECHAT, ALIPAY, CASH, BANK, OTHER }

/** 流水来源通道 */
enum class TxnSource { NOTIFICATION, ACCESSIBILITY, CSV, MANUAL, MERGED }

/** 流水状态 */
enum class TxnStatus { CONFIRMED, PENDING, IGNORED }

/** 分类的来源：手动指定的永不被规则覆盖（§6.7.1） */
enum class CategorySource { NONE, MANUAL, RULE, BUILTIN, DEFAULT }

/** 时间精度：账单只有日期时为 DAY（§6.4.2） */
enum class TimePrecision { INSTANT, DAY }

/** 规则匹配字段 */
enum class MatchField { COUNTERPARTY, GOODS, RAW_TEXT, CHANNEL }

/** 规则匹配方式 */
enum class MatchType { CONTAINS, STARTS_WITH, EQUALS, REGEX }

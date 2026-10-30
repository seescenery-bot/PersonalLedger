package com.mizhii.ledger.data.import

import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.TimePrecision
import com.mizhii.ledger.data.db.entity.TxnSource
import com.mizhii.ledger.domain.model.TxnCandidate
import com.mizhii.ledger.util.Money
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 账单文件解析（需求文档 §7.9 / §8.6，M4）。
 *
 * 支持的输入：
 *  - 微信「下载账单」导出的 **xlsx**（真机实测：`微信支付账单流水文件(起-止)_时间戳.xlsx`）
 *  - 微信 / 支付宝导出的 **CSV**（UTF-8-BOM 或 GBK）
 *
 * 两种格式共用同一套"元信息 + 分隔行 + 表头 + 数据行"布局，所以流程是统一的：
 *
 *   1. [readTable]  容器 → 二维字符串表（xlsx 用 [XlsxReader]，其余按 CSV 读并探测编码）
 *   2. [parse]      定位表头行 → 建列映射 → 逐行转 [Entry] → 顺手解析账单自带的汇总数字
 *
 * 关键设计：**账单自己声明的汇总数字必须用来对账**。
 * 微信账单里有 `收入：11笔 3849.69元 / 支出：39笔 496.47元 / 中性交易：3笔 2692.59元`，
 * 导入后逐项核对，对不上就报错而不是默默入库 —— 这是唯一能发现"解析漏列/漏行"的手段。
 *
 * 真机校准记录（2026-10-29，微信 53 笔账单）：
 *  - 日期在 xlsx 里是 **Excel 序列号**（`46324.720034722224`），CSV 里是 `2026-10-29 17:16:51`
 *  - 金额在 xlsx 里是**裸数字**（`5` / `200.2`），CSV 里带 `¥`
 *  - `收/支` 列的第三種取值是 **`/`**，即「中性交易」（零钱提现等），**不计入收支**
 *  - 退款是**独立的一行收入**（交易类型 `xxx-退款`），不是原支出的状态变更
 */
object BillFile {

    enum class Kind { WECHAT, ALIPAY, UNKNOWN }

    /** 账单声明的时间基准。微信账单明写"所有时间均为 UTC+08:00"。 */
    private val BILL_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** Excel 序列号的零点。注意 Excel 有 1900-02-29 的历史 bug，序列号 < 61 会差一天，账单用不到。 */
    private val EXCEL_EPOCH: LocalDateTime = LocalDateTime.of(1899, 12, 30, 0, 0)

    data class Table(
        val rows: List<List<String>>,
        val encoding: String,
        val container: String,
    )

    /** 账单开头自带的汇总，用于导入后对账 */
    data class Summary(
        val totalCount: Int? = null,
        val incomeCount: Int? = null,
        val incomeCents: Long? = null,
        val expenseCount: Int? = null,
        val expenseCents: Long? = null,
        val neutralCount: Int? = null,
        val neutralCents: Long? = null,
    ) {
        val isEmpty: Boolean
            get() = totalCount == null && incomeCount == null && expenseCount == null && neutralCount == null
    }

    data class Entry(
        val occurredAt: Long,
        /** null = 中性交易（不计收支），如零钱提现 */
        val direction: Direction?,
        val amountCents: Long,
        val counterparty: String?,
        val goods: String?,
        val payMethod: String?,
        val status: String?,
        val tradeType: String?,
        val externalNo: String?,
        val merchantNo: String?,
        val remark: String?,
        val isRefund: Boolean,
        val rawRow: String,
    )

    /** 逐项核对结果 */
    data class Reconcile(
        val incomeCount: Int,
        val incomeCents: Long,
        val expenseCount: Int,
        val expenseCents: Long,
        val neutralCount: Int,
        val neutralCents: Long,
    ) {
        val totalCount: Int get() = incomeCount + expenseCount + neutralCount
    }

    data class Parsed(
        val kind: Kind,
        val channel: Channel,
        val encoding: String,
        val headerRowIndex: Int,
        val summary: Summary,
        /** 收入 + 支出，可入账 */
        val entries: List<Entry>,
        /** 中性交易（提现等），不入账但要计数 */
        val neutral: List<Entry>,
        /** 表头以下无法解析的行（页脚、空行等） */
        val unparsedRowCount: Int,
    ) {
        fun computed(): Reconcile {
            fun sum(list: List<Entry>) = list.sumOf { it.amountCents }
            val income = entries.filter { it.direction == Direction.INCOME }
            val expense = entries.filter { it.direction == Direction.EXPENSE }
            return Reconcile(
                incomeCount = income.size,
                incomeCents = sum(income),
                expenseCount = expense.size,
                expenseCents = sum(expense),
                neutralCount = neutral.size,
                neutralCents = sum(neutral),
            )
        }

        /** 账单声明 vs 实际解析，逐项检查；返回不一致项的描述（空 = 完全一致） */
        fun reconcileProblems(): List<String> {
            val c = computed()
            val s = summary
            val problems = mutableListOf<String>()
            fun check(label: String, declared: Int?, actual: Int) {
                if (declared != null && declared != actual) {
                    problems += "$label 笔数不一致：账单声明 $declared，实际解析 $actual"
                }
            }
            fun checkCents(label: String, declared: Long?, actual: Long) {
                if (declared != null && declared != actual) {
                    problems += "$label 金额不一致：账单声明 ${Money.format(declared)}，实际解析 ${Money.format(actual)}"
                }
            }
            check("收入", s.incomeCount, c.incomeCount)
            check("支出", s.expenseCount, c.expenseCount)
            check("中性交易", s.neutralCount, c.neutralCount)
            check("总计", s.totalCount, c.totalCount)
            checkCents("收入", s.incomeCents, c.incomeCents)
            checkCents("支出", s.expenseCents, c.expenseCents)
            checkCents("中性交易", s.neutralCents, c.neutralCents)
            return problems
        }
    }

    // ── 1. 容器 → 二维表 ────────────────────────────────────────────

    fun readTable(fileName: String, bytes: ByteArray): Table {
        val isZip = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()
        return if (isZip || fileName.endsWith(".xlsx", ignoreCase = true)) {
            val rows = XlsxReader.readRows(bytes.inputStream())
            Table(rows, encoding = "XLSX", container = "xlsx")
        } else {
            val csv = CsvReader.read(bytes)
            Table(csv.rows, encoding = csv.encoding, container = "csv")
        }
    }

    // ── 2. 解析 ────────────────────────────────────────────────────

    fun parse(table: Table): Parsed {
        val headerIndex = findHeaderRow(table.rows)
            ?: throw IllegalArgumentException("找不到表头行（需要同时包含「交易时间」和「收/支」两列）")
        val columns = Columns(table.rows[headerIndex])
        val kind = detectKind(columns)
        val summary = parseSummary(table.rows, headerIndex)

        val entries = ArrayList<Entry>()
        val neutral = ArrayList<Entry>()
        var unparsed = 0

        for (row in table.rows.drop(headerIndex + 1)) {
            if (row.all { it.isBlank() }) continue
            val entry = parseRow(row, columns)
            if (entry == null) {
                unparsed++
                continue
            }
            if (entry.direction == null) neutral.add(entry) else entries.add(entry)
        }

        return Parsed(
            kind = kind,
            channel = when (kind) {
                Kind.WECHAT -> Channel.WECHAT
                Kind.ALIPAY -> Channel.ALIPAY
                Kind.UNKNOWN -> Channel.OTHER
            },
            encoding = table.encoding,
            headerRowIndex = headerIndex,
            summary = summary,
            entries = entries,
            neutral = neutral,
            unparsedRowCount = unparsed,
        )
    }

    private fun findHeaderRow(rows: List<List<String>>): Int? {
        for ((i, row) in rows.withIndex().take(40)) {
            val norm = row.map { normalize(it) }
            val hasTime = norm.any { it == "交易时间" }
            val hasIo = norm.any { it == "收/支" || it == "收支" }
            if (hasTime && hasIo) return i
        }
        return null
    }

    private fun detectKind(columns: Columns): Kind {
        val typeIdx = columns.tradeType
        val typeHeader = if (typeIdx != null) normalize(columns.header(typeIdx)) else ""
        val merchantIdx = columns.merchantNo
        val merchantHeader = if (merchantIdx != null) columns.header(merchantIdx) else ""
        return when {
            typeHeader == "交易类型" -> Kind.WECHAT
            typeHeader == "交易分类" -> Kind.ALIPAY
            merchantHeader.contains("商户") -> Kind.WECHAT
            else -> Kind.UNKNOWN
        }
    }

    private fun parseRow(row: List<String>, columns: Columns): Entry? {
        val time = parseTime(columns.cell(row, columns.time)) ?: return null
        val amountCents = parseMoney(columns.cell(row, columns.amount)) ?: return null

        val io = normalize(columns.cell(row, columns.incomeExpense))
        val direction = when (io) {
            "支出" -> Direction.EXPENSE
            "收入" -> Direction.INCOME
            else -> null // "/"、"不计收支"（支付宝）、"中性交易"（微信）
        }

        // 0 元记录：
        //  - 不收不支类要保留（支付宝真实存在「芝麻免押下单成功 / 解冻成功」的 ¥0.00 记录，
        //    它们会计入账单声明的总笔数，丢掉就永远对不上账）；
        //  - 收支类的 0 元没有意义，视为脏行。
        if (amountCents <= 0 && direction != null) return null

        val tradeType = columns.cell(row, columns.tradeType).nullIfBlank()
        val status = columns.cell(row, columns.status).nullIfBlank()
        val counterparty = columns.cell(row, columns.counterparty).nullIfBlank()?.nullIfSlash()
        val goods = columns.cell(row, columns.goods).nullIfBlank()?.nullIfSlash()
        val remark = columns.cell(row, columns.remark).nullIfBlank()?.nullIfSlash()

        val isRefund = isRefund(tradeType, status)

        return Entry(
            occurredAt = time,
            direction = direction,
            amountCents = amountCents,
            counterparty = counterparty,
            goods = goods,
            payMethod = columns.cell(row, columns.payMethod).nullIfBlank()?.nullIfSlash(),
            status = status,
            tradeType = tradeType,
            externalNo = columns.cell(row, columns.externalNo).nullIfBlank()?.nullIfSlash(),
            merchantNo = columns.cell(row, columns.merchantNo).nullIfBlank()?.nullIfSlash(),
            remark = remark,
            isRefund = isRefund,
            rawRow = row.filter { it.isNotBlank() }.joinToString(" | ").take(500),
        )
    }

    /** 退款在账单里是**独立的一行收入**（交易类型带「退款」，或状态带「已退款」） */
    internal fun isRefund(tradeType: String?, status: String?): Boolean {
        val type = tradeType.orEmpty()
        val st = status.orEmpty()
        // 「已全额退款 / 已退款¥2.60」都算；纯「支付成功」不算
        return type.contains("退款") || st.contains("退款")
    }

    // ── 金额 / 时间 ───────────────────────────────────────────────

    internal fun parseMoney(raw: String): Long? {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return null
        if (cleaned == "/" || cleaned == "-") return null
        return Money.parseYuanToCents(cleaned)
    }

    private val DATETIME_PATTERNS = listOf(
        Regex("""(\d{4})-(\d{1,2})-(\d{1,2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?"""),
        Regex("""(\d{4})/(\d{1,2})/(\d{1,2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?"""),
        Regex("""(\d{4})-(\d{1,2})-(\d{1,2})"""),
    )

    internal fun parseTime(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty() || s == "/") return null

        for (pattern in DATETIME_PATTERNS) {
            val m = pattern.find(s) ?: continue
            val g = m.groupValues
            val parsed = runCatching {
                val hour = g.getOrNull(4)?.toIntOrNull() ?: 0
                val minute = g.getOrNull(5)?.toIntOrNull() ?: 0
                val second = g.getOrNull(6)?.toIntOrNull() ?: 0
                LocalDateTime.of(
                    g[1].toInt(), g[2].toInt(), g[3].toInt(), hour, minute, second
                ).atZone(BILL_ZONE).toInstant().toEpochMilli()
            }.getOrNull()
            if (parsed != null) return parsed
        }

        // xlsx 里日期是 Excel 序列号（自 1899-12-30 起的天数，小数部分是一天的比例）
        val serial = s.toDoubleOrNull()
        if (serial != null && serial > 20_000 && serial < 80_000) {
            return excelSerialToMillis(serial)
        }
        return null
    }

    /** Excel 序列号 → 毫秒。账单声明是 UTC+08:00，所以按固定时区解释，不跟随设备时区。 */
    internal fun excelSerialToMillis(serial: Double): Long {
        val totalSeconds = Math.round(serial * 86_400.0)
        return EXCEL_EPOCH.plusSeconds(totalSeconds)
            .atZone(BILL_ZONE)
            .toInstant()
            .toEpochMilli()
    }

    // ── 汇总数字 ───────────────────────────────────────────────────

    // 汇总文案两家不一样，都要认：
    //   微信：`收入：11笔 3849.69元` / `支出：39笔 496.47元` / `中性交易：3笔 2692.59元`
    //   支付宝：`收入：2笔 500.00元` / `支出：185笔 9935.42元` / `不计收支：69笔 23508.67元`
    //
    // ⚠️ 「不计收支：69笔」里含有子串「支出：」，如果 R_EXPENSE 不加边界就会把
    // 69 笔 23508.67 元当成支出 —— 支付宝账单真机踩到的坑。`(?:^|[\s　])` 用来挡这个。
    private val R_INCOME = Regex("""(?:^|[\s　])收入[:：]\s*(\d+)\s*笔\s*([\d,]+(?:\.\d{1,2})?)\s*元""")
    private val R_EXPENSE = Regex("""(?:^|[\s　])支出[:：]\s*(\d+)\s*笔\s*([\d,]+(?:\.\d{1,2})?)\s*元""")

    /** 中性交易：微信叫「中性交易」，支付宝叫「不计收支」 */
    private val R_NEUTRAL = Regex("""(?:中性交易|不计收支)[:：]\s*(\d+)\s*笔\s*([\d,]+(?:\.\d{1,2})?)\s*元""")
    private val R_TOTAL = Regex("""共\s*(\d+)\s*笔""")

    private fun parseSummary(rows: List<List<String>>, headerIndex: Int): Summary {
        var total: Int? = null
        var incomeCount: Int? = null
        var incomeCents: Long? = null
        var expenseCount: Int? = null
        var expenseCents: Long? = null
        var neutralCount: Int? = null
        var neutralCents: Long? = null

        // 汇总区在表头之前；支付宝的在文件末尾也有，所以两处都扫
        val candidates = rows.take(headerIndex) + rows.drop(headerIndex + 1)
        for (row in candidates) {
            for (cell in row) {
                if (cell.isBlank()) continue
                R_TOTAL.find(cell)?.let { m ->
                    if (total == null) total = m.groupValues[1].toIntOrNull()
                }
                R_INCOME.find(cell)?.let { m ->
                    if (incomeCount == null) {
                        incomeCount = m.groupValues[1].toIntOrNull()
                        incomeCents = Money.parseYuanToCents(m.groupValues[2])
                    }
                }
                R_EXPENSE.find(cell)?.let { m ->
                    if (expenseCount == null) {
                        expenseCount = m.groupValues[1].toIntOrNull()
                        expenseCents = Money.parseYuanToCents(m.groupValues[2])
                    }
                }
                R_NEUTRAL.find(cell)?.let { m ->
                    if (neutralCount == null) {
                        neutralCount = m.groupValues[1].toIntOrNull()
                        neutralCents = Money.parseYuanToCents(m.groupValues[2])
                    }
                }
            }
        }
        return Summary(
            totalCount = total,
            incomeCount = incomeCount, incomeCents = incomeCents,
            expenseCount = expenseCount, expenseCents = expenseCents,
            neutralCount = neutralCount, neutralCents = neutralCents,
        )
    }

    // ── 工具 ───────────────────────────────────────────────────────

    internal fun normalize(s: String): String = s.trim()
        .replace(" ", "")
        .replace("\u00A0", "")
        .replace("\u3000", "")
        .replace('（', '(')
        .replace('）', ')')

    private fun String.nullIfBlank(): String? = trim().takeIf { it.isNotEmpty() && it != "/" }

    private fun String.nullIfSlash(): String? = trim().takeIf { it.isNotEmpty() && it != "/" }

    /** 列索引容器：按表头名做「精确 → 包含」两级匹配 */
    private class Columns(private val headers: List<String>) {

        private val normalized = headers.map { normalize(it) }

        fun header(index: Int): String = headers.getOrElse(index) { "" }

        fun idx(vararg candidates: String): Int? {
            val norms = candidates.map { normalize(it) }
            for (target in norms) {
                val exact = normalized.indexOfFirst { it == target }
                if (exact >= 0) return exact
            }
            for (target in norms) {
                if (target.isEmpty()) continue
                val contains = normalized.indexOfFirst { it.contains(target) }
                if (contains >= 0) return contains
            }
            return null
        }

        fun cell(row: List<String>, index: Int?): String =
            if (index == null) "" else row.getOrElse(index) { "" }

        val time: Int? = idx("交易时间", "交易创建时间")
        val incomeExpense: Int? = idx("收/支", "收支", "收支类型")
        val amount: Int? = idx("金额(元)", "金额", "交易金额")
        val counterparty: Int? = idx("交易对方", "对方")
        val goods: Int? = idx("商品", "商品说明", "商品名称")
        val payMethod: Int? = idx("支付方式", "收/付款方式", "付款方式")
        val status: Int? = idx("当前状态", "交易状态", "状态")
        val externalNo: Int? = idx("交易单号", "交易订单号", "交易号")
        val merchantNo: Int? = idx("商户单号", "商家订单号")
        val remark: Int? = idx("备注")
        val tradeType: Int? = idx("交易类型", "交易分类", "类型")
    }
}

/**
 * 账单行 → 候选流水。
 *
 * 账单是**权威数据源**，因此 `source = CSV`，且默认不强制待确认
 * （唯一例外：收入完全没有对方名时仍强制待确认，与通道 A/B 的口径一致）。
 *
 * 分类建议优先级：
 *  1. 退款 —— 由状态/类型直接确定，最可靠
 *  2. **支付宝的「交易分类」** —— 真实消费品类，真机覆盖率 182/185（见 [AlipayTradeCategory]）
 *  3. 微信/其他：收入且类型含「转账」→ 往来款
 */
fun BillFile.Entry.toCandidate(channel: Channel): TxnCandidate {
    val dir = direction ?: error("中性交易不应转成候选流水")
    val byChannel = if (channel == Channel.ALIPAY) {
        AlipayTradeCategory.categoryOf(tradeType, dir)
    } else {
        null
    }
    val suggested = when {
        isRefund -> "refund"
        byChannel != null -> byChannel
        dir == Direction.INCOME && tradeType.orEmpty().contains("转账") -> "transfer_in"
        else -> null
    }
    return TxnCandidate(
        channel = channel,
        direction = dir,
        amountCents = amountCents,
        occurredAt = occurredAt,
        timePrecision = TimePrecision.INSTANT,
        counterparty = counterparty,
        goods = goods,
        externalNo = externalNo,
        merchantNo = merchantNo,
        payMethod = payMethod,
        rawText = rawRow,
        source = TxnSource.CSV,
        originTitle = listOfNotNull(tradeType, status).joinToString(" / ").ifBlank { null },
        suggestedCategoryId = suggested,
        forcePending = dir == Direction.INCOME && counterparty == null
    )
}
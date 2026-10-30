package com.mizhii.ledger.domain.backup

import com.mizhii.ledger.crypto.CorruptBackupException
import com.mizhii.ledger.data.db.entity.CategoryEntity
import com.mizhii.ledger.data.db.entity.CategorySource
import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.IgnoreEntryEntity
import com.mizhii.ledger.data.db.entity.ImportBatchEntity
import com.mizhii.ledger.data.db.entity.MatchField
import com.mizhii.ledger.data.db.entity.MatchType
import com.mizhii.ledger.data.db.entity.RuleEntity
import com.mizhii.ledger.data.db.entity.TimePrecision
import com.mizhii.ledger.data.db.entity.TxnEntity
import com.mizhii.ledger.data.db.entity.TxnSource
import com.mizhii.ledger.data.db.entity.TxnStatus

/** 备份文件里除密文之外的全部内容 */
data class BackupContent(
    val formatVersion: Int,
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val categories: List<CategoryEntity>,
    val rules: List<RuleEntity>,
    val txns: List<TxnEntity>,
    val ignoreEntries: List<IgnoreEntryEntity>,
    val importBatches: List<ImportBatchEntity>,
    val settings: Map<String, String>,
)

/**
 * 备份正文的**序列化格式**（纯函数，可单测）。
 *
 * 为什么不用 JSON：项目里没有任何 JSON 库，自己手写解析器（几百行、容易有边界 bug）
 * 不如用一个自证的简单格式。整个正文都在 AES-GCM 密文里，**不需要可读性**，
 * 只需要「绝对能原样还原」。
 *
 * 布局：
 * ```
 * #JIANZHANG-BACKUP
 * M<ver>\u001F<schemaVer>\u001F<appVersion>\u001F<createdAt>
 * C<id>\u001F<name>...
 * T<id>\u001F...
 * R...
 * I...
 * B...
 * S<key>\u001F<value>
 * ```
 *
 * 字段用 `U+001F` 分隔、记录用 `\n` 分隔；
 * 字段内部的 `\` / `U+001F` / `\n` / `\r` 会被转义，所以分隔符永远只有一个含义。
 */
object BackupFormat {

    const val MAGIC: String = "#JIANZHANG-BACKUP"
    const val VERSION: Int = 1

    private const val SEP = '\u001F'
    private const val ESC = '\\'

    // ── 序列化 ─────────────────────────────────────────────────────

    fun encode(content: BackupContent): ByteArray {
        val sb = StringBuilder(1024 + content.txns.size * 160)
        sb.append(MAGIC).append('\n')
        line(
            sb, "M",
            content.formatVersion.toString(),
            content.schemaVersion.toString(),
            content.appVersion,
            content.createdAt.toString()
        )
        for (c in content.categories) {
            line(
                sb, "C",
                c.id, c.name, c.parentId, c.direction?.name, c.iconKey, c.colorHex,
                c.sortOrder.toString(), c.isBuiltin.toString(), c.isArchived.toString()
            )
        }
        for (r in content.rules) {
            line(
                sb, "R",
                r.id, r.priority.toString(), r.matchField.name, r.matchType.name,
                r.pattern, r.categoryId, r.enabled.toString(), r.isBuiltin.toString(),
                r.hitCount.toString(), r.createdAt.toString()
            )
        }
        for (t in content.txns) {
            line(
                sb, "T",
                t.id.toString(), t.occurredAt.toString(), t.dayKey, t.amountCents.toString(),
                t.direction.name, t.channel.name, t.counterparty, t.goods, t.externalNo,
                t.merchantNo, t.payMethod, t.categoryId, t.categorySource.name, t.note,
                t.source.name, t.sourceDetail, t.confidence.toString(), t.status.name,
                t.timePrecision.name, t.rawText, t.rowHash, t.importBatchId,
                t.createdAt.toString(), t.updatedAt.toString()
            )
        }
        for (i in content.ignoreEntries) {
            line(
                sb, "I",
                i.id.toString(), i.externalNo, i.channel.name, i.reason, i.createdAt.toString()
            )
        }
        for (b in content.importBatches) {
            line(
                sb, "B",
                b.id, b.channel.name, b.fileName, b.fileSha256, b.encoding,
                b.rowCount.toString(), b.newCount.toString(), b.mergedCount.toString(),
                b.skippedCount.toString(), b.importedAt.toString()
            )
        }
        for ((k, v) in content.settings) {
            line(sb, "S", k, v)
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun line(sb: StringBuilder, tag: String, vararg fields: String?) {
        sb.append(tag)
        for (f in fields) {
            sb.append(SEP).append(escape(f))
        }
        sb.append('\n')
    }

    private fun escape(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder(raw.length + 8)
        for (ch in raw) {
            when (ch) {
                ESC -> sb.append(ESC).append(ESC)
                SEP -> sb.append(ESC).append('s')
                '\n' -> sb.append(ESC).append('n')
                '\r' -> sb.append(ESC).append('r')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ── 反序列化 ───────────────────────────────────────────────────

    fun decode(bytes: ByteArray): BackupContent {
        val text = bytes.toString(Charsets.UTF_8)
        val lines = text.split('\n')
        if (lines.isEmpty() || lines[0].trim() != MAGIC) {
            throw CorruptBackupException("备份正文的魔数不对，文件可能损坏")
        }

        var formatVersion = 0
        var schemaVersion = 0
        var appVersion = ""
        var createdAt = 0L
        val categories = ArrayList<CategoryEntity>()
        val rules = ArrayList<RuleEntity>()
        val txns = ArrayList<TxnEntity>()
        val ignores = ArrayList<IgnoreEntryEntity>()
        val batches = ArrayList<ImportBatchEntity>()
        val settings = LinkedHashMap<String, String>()

        for (idx in 1 until lines.size) {
            val raw = lines[idx]
            if (raw.isBlank()) continue
            val tag = raw[0]
            // 记录形如 `T<SEP>字段1<SEP>字段2`：去掉行首标签后，
            // 第一个字符就是分隔符，直接 split 会多出一个空元素，必须丢掉。
            val parts = raw.substring(1).split(SEP)
            val f: List<String?> =
                (if (parts.isNotEmpty() && parts[0].isEmpty()) parts.drop(1) else parts).map(::unescape)
            try {
                when (tag) {
                    'M' -> {
                        formatVersion = f.int(0)
                        schemaVersion = f.int(1)
                        appVersion = f.str(2)
                        createdAt = f.long(3)
                    }
                    'C' -> categories.add(
                        CategoryEntity(
                            id = f.str(0),
                            name = f.str(1),
                            parentId = f.nullable(2),
                            direction = f.enumOrNull<Direction>(3),
                            iconKey = f.str(4),
                            colorHex = f.str(5),
                            sortOrder = f.int(6),
                            isBuiltin = f.bool(7),
                            isArchived = f.bool(8),
                        )
                    )
                    'R' -> rules.add(
                        RuleEntity(
                            id = f.str(0),
                            priority = f.int(1),
                            matchField = f.enum<MatchField>(2),
                            matchType = f.enum<MatchType>(3),
                            pattern = f.str(4),
                            categoryId = f.str(5),
                            enabled = f.bool(6),
                            isBuiltin = f.bool(7),
                            hitCount = f.int(8),
                            createdAt = f.long(9),
                        )
                    )
                    'T' -> txns.add(
                        TxnEntity(
                            id = f.long(0),
                            occurredAt = f.long(1),
                            dayKey = f.str(2),
                            amountCents = f.long(3),
                            direction = f.enum<Direction>(4),
                            channel = f.enum<Channel>(5),
                            counterparty = f.nullable(6),
                            goods = f.nullable(7),
                            externalNo = f.nullable(8),
                            merchantNo = f.nullable(9),
                            payMethod = f.nullable(10),
                            categoryId = f.nullable(11),
                            categorySource = f.enum<CategorySource>(12),
                            note = f.nullable(13),
                            source = f.enum<TxnSource>(14),
                            sourceDetail = f.nullable(15),
                            confidence = f.int(16),
                            status = f.enum<TxnStatus>(17),
                            timePrecision = f.enum<TimePrecision>(18),
                            rawText = f.nullable(19),
                            rowHash = f.str(20),
                            importBatchId = f.nullable(21),
                            createdAt = f.long(22),
                            updatedAt = f.long(23),
                        )
                    )
                    'I' -> ignores.add(
                        IgnoreEntryEntity(
                            id = f.long(0),
                            externalNo = f.str(1),
                            channel = f.enum<Channel>(2),
                            reason = f.nullable(3),
                            createdAt = f.long(4),
                        )
                    )
                    'B' -> batches.add(
                        ImportBatchEntity(
                            id = f.str(0),
                            channel = f.enum<Channel>(1),
                            fileName = f.str(2),
                            fileSha256 = f.str(3),
                            encoding = f.str(4),
                            rowCount = f.int(5),
                            newCount = f.int(6),
                            mergedCount = f.int(7),
                            skippedCount = f.int(8),
                            importedAt = f.long(9),
                        )
                    )
                    'S' -> settings[f.str(0)] = f.str(1)
                    else -> throw CorruptBackupException("未知的记录类型 '$tag'（第 ${idx + 1} 行）")
                }
            } catch (e: Exception) {
                // 所有解析错误都带上行号和记录类型 —— 恢复失败时用户至少知道文件坏在哪
                val detail = e.message ?: e.javaClass.simpleName
                throw CorruptBackupException("第 ${idx + 1} 行（类型 $tag）解析失败：$detail")
            }
        }

        if (formatVersion == 0) throw CorruptBackupException("备份缺少 META 段")
        if (formatVersion > VERSION) {
            throw CorruptBackupException("备份格式版本 $formatVersion 比本版本新，请升级 App")
        }

        return BackupContent(
            formatVersion = formatVersion,
            schemaVersion = schemaVersion,
            appVersion = appVersion,
            createdAt = createdAt,
            categories = categories,
            rules = rules,
            txns = txns,
            ignoreEntries = ignores,
            importBatches = batches,
            settings = settings,
        )
    }

    private fun unescape(raw: String): String? {
        if (raw.isEmpty()) return null
        if (raw.indexOf(ESC) < 0) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == ESC && i + 1 < raw.length) {
                when (val n = raw[i + 1]) {
                    ESC -> sb.append(ESC)
                    's' -> sb.append(SEP)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    // ── 字段读取器（越界/格式错都抛 CorruptBackupException）──────────

    private fun List<String?>.at(i: Int): String? {
        if (i >= size) throw CorruptBackupException("字段数不足，缺少第 ${i + 1} 个字段")
        return this[i]
    }

    private fun List<String?>.str(i: Int): String = at(i) ?: ""
    private fun List<String?>.nullable(i: Int): String? = at(i)
    private fun List<String?>.long(i: Int): Long =
        str(i).toLongOrNull() ?: throw CorruptBackupException("字段 ${i + 1} 不是整数：${at(i)}")
    private fun List<String?>.int(i: Int): Int =
        str(i).toIntOrNull() ?: throw CorruptBackupException("字段 ${i + 1} 不是整数：${at(i)}")
    private fun List<String?>.bool(i: Int): Boolean = str(i).toBooleanStrictOrNull()
        ?: throw CorruptBackupException("字段 ${i + 1} 不是布尔：${at(i)}")

    private inline fun <reified T : Enum<T>> List<String?>.enum(i: Int): T =
        enumOrNull<T>(i) ?: throw CorruptBackupException("字段 ${i + 1} 不是合法的 ${T::class.simpleName}：${at(i)}")

    private inline fun <reified T : Enum<T>> List<String?>.enumOrNull(i: Int): T? {
        val raw = at(i) ?: return null
        return enumValues<T>().firstOrNull { it.name == raw }
            ?: throw CorruptBackupException("未知的 ${T::class.simpleName} 取值：$raw")
    }
}

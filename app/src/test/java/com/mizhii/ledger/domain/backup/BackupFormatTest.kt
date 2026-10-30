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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份正文格式（M5 §7.6）。
 *
 * 这是"备份能不能救回数据"的最后一道关口：只要有一个字段不是**原样**往返，
 * 恢复出来的账本就和原来不一样了。所以这里逐字段比对，而不是只比条数。
 */
class BackupFormatTest {

    private fun sampleContent() = BackupContent(
        formatVersion = BackupFormat.VERSION,
        schemaVersion = 1,
        appVersion = "0.1.0",
        createdAt = 1_793_000_000_000L,
        categories = listOf(
            CategoryEntity(
                id = "cat_food", name = "餐饮", parentId = null, direction = Direction.EXPENSE,
                iconKey = "restaurant", colorHex = "#FFE8A33D", sortOrder = 1,
                isBuiltin = true, isArchived = false
            ),
            CategoryEntity(
                id = "cat_custom", name = "自定义分类", parentId = "cat_food",
                direction = null, iconKey = "star", colorHex = "#FF888888", sortOrder = 9,
                isBuiltin = false, isArchived = true
            ),
        ),
        rules = listOf(
            RuleEntity(
                id = "r1", priority = 10, matchField = MatchField.COUNTERPARTY,
                matchType = MatchType.CONTAINS, pattern = "便利店", categoryId = "cat_food",
                enabled = true, isBuiltin = false, hitCount = 42, createdAt = 111L
            )
        ),
        txns = listOf(
            TxnEntity(
                id = 7, occurredAt = 1_793_265_411_000L, dayKey = "2026-10-29",
                amountCents = 1250, direction = Direction.EXPENSE, channel = Channel.WECHAT,
                counterparty = "示例便利店", goods = "商品A", externalNo = "WX001",
                merchantNo = "M001", payMethod = "零钱", categoryId = "cat_food",
                categorySource = CategorySource.RULE, note = "备注",
                source = TxnSource.NOTIFICATION, sourceDetail = "detail",
                confidence = 85, status = TxnStatus.PENDING,
                timePrecision = TimePrecision.INSTANT, rawText = "原始文本",
                rowHash = "hash-1", importBatchId = null,
                createdAt = 111L, updatedAt = 222L
            ),
            TxnEntity(
                id = 8, occurredAt = 1_793_000_000_000L, dayKey = "2026-10-27",
                amountCents = 300_000, direction = Direction.INCOME, channel = Channel.ALIPAY,
                counterparty = null, goods = null, externalNo = null, merchantNo = null,
                payMethod = null, categoryId = null, categorySource = CategorySource.NONE,
                note = null, source = TxnSource.CSV, sourceDetail = null, confidence = 100,
                status = TxnStatus.CONFIRMED, timePrecision = TimePrecision.DAY,
                rawText = null, rowHash = "hash-2", importBatchId = "batch-1",
                createdAt = 333L, updatedAt = 444L
            ),
        ),
        ignoreEntries = listOf(
            IgnoreEntryEntity(
                id = 3, externalNo = "WX999", channel = Channel.WECHAT,
                reason = "用户手动删除", createdAt = 555L
            )
        ),
        importBatches = listOf(
            ImportBatchEntity(
                id = "batch-1", channel = Channel.ALIPAY, fileName = "bill.csv",
                fileSha256 = "abc123", encoding = "GB18030", rowCount = 12,
                newCount = 9, mergedCount = 1, skippedCount = 3, importedAt = 666L
            )
        ),
        settings = linkedMapOf(
            "autoConfirmThreshold" to "70",
            "notifyOnCapture" to "true",
        ),
    )

    @Test
    fun `整份内容能原样往返`() {
        val original = sampleContent()
        val decoded = BackupFormat.decode(BackupFormat.encode(original))

        assertEquals(original.formatVersion, decoded.formatVersion)
        assertEquals(original.schemaVersion, decoded.schemaVersion)
        assertEquals(original.appVersion, decoded.appVersion)
        assertEquals(original.createdAt, decoded.createdAt)
        assertEquals(original.categories, decoded.categories)
        assertEquals(original.rules, decoded.rules)
        assertEquals(original.txns, decoded.txns)
        assertEquals(original.ignoreEntries, decoded.ignoreEntries)
        assertEquals(original.importBatches, decoded.importBatches)
        assertEquals(original.settings, decoded.settings)
    }

    @Test
    fun `空备份能原样往返`() {
        val empty = BackupContent(
            formatVersion = BackupFormat.VERSION, schemaVersion = 1, appVersion = "0.1.0",
            createdAt = 1L, categories = emptyList(), rules = emptyList(), txns = emptyList(),
            ignoreEntries = emptyList(), importBatches = emptyList(), settings = emptyMap()
        )
        val decoded = BackupFormat.decode(BackupFormat.encode(empty))
        assertEquals(0, decoded.txns.size)
        assertEquals(0, decoded.categories.size)
    }

    @Test
    fun `特殊字符不会被分隔符或换行打断`() {
        val nasty = "换行\n回车\r分隔符\u001F反斜杠\\ 斜杠/ 制表\t 表情😀 \"引号\" ,逗号"
        val content = sampleContent().copy(
            txns = listOf(
                sampleContent().txns[0].copy(
                    counterparty = nasty,
                    goods = "\u001F",
                    note = "\\",
                    rawText = "\n\n",
                )
            )
        )
        val decoded = BackupFormat.decode(BackupFormat.encode(content))
        assertEquals(nasty, decoded.txns[0].counterparty)
        assertEquals("\u001F", decoded.txns[0].goods)
        assertEquals("\\", decoded.txns[0].note)
        assertEquals("\n\n", decoded.txns[0].rawText)
    }

    @Test
    fun `可空字段的 null 与空串都还原成 null`() {
        val decoded = BackupFormat.decode(BackupFormat.encode(sampleContent()))
        val second = decoded.txns[1]
        assertNull(second.counterparty)
        assertNull(second.goods)
        assertNull(second.externalNo)
        assertNull(second.categoryId)
        assertNull(second.note)
        assertNull(second.sourceDetail)
        assertNull(second.rawText)
        // 非空的少数字段必须还在
        assertEquals("batch-1", second.importBatchId)
        assertEquals("hash-2", second.rowHash)
    }

    @Test
    fun `魔数不对要说清楚`() {
        val e = runCatching { BackupFormat.decode("随便什么内容".toByteArray()) }.exceptionOrNull()
        assertTrue(e is CorruptBackupException)
        assertTrue("提示应提到魔数", e!!.message!!.contains("魔数"))
    }

    @Test
    fun `字段缺失要报行号而不是静默丢数据`() {
        val broken = """
            #JIANZHANG-BACKUP
            M1${'\u001F'}1${'\u001F'}0.1.0${'\u001F'}1
            C仅两个字段${'\u001F'}名字
        """.trimIndent().toByteArray()
        val e = runCatching { BackupFormat.decode(broken) }.exceptionOrNull()
        assertTrue("应抛 CorruptBackupException，实际 $e", e is CorruptBackupException)
        assertTrue("提示应带行号", e!!.message!!.contains("第 3 行"))
    }

    @Test
    fun `未知枚举值要明确拒绝`() {
        val broken = """
            #JIANZHANG-BACKUP
            M1${'\u001F'}1${'\u001F'}0.1.0${'\u001F'}1
            T1${'\u001F'}1${'\u001F'}2026-10-29${'\u001F'}100${'\u001F'}UP${'\u001F'}WECHAT${'\u001F'}${'\u001F'}${'\u001F'}${'\u001F'}${'\u001F'}${'\u001F'}${'\u001F'}NONE${'\u001F'}${'\u001F'}MANUAL${'\u001F'}${'\u001F'}100${'\u001F'}CONFIRMED${'\u001F'}INSTANT${'\u001F'}${'\u001F'}hash${'\u001F'}${'\u001F'}1${'\u001F'}1
        """.trimIndent().toByteArray()
        val e = runCatching { BackupFormat.decode(broken) }.exceptionOrNull()
        assertTrue("应抛 CorruptBackupException，实际 $e", e is CorruptBackupException)
        assertTrue("提示应提到未知取值，实际 ${e!!.message}", e.message!!.contains("UP"))
    }

    @Test
    fun `更新的格式版本要求升级 App`() {
        val future = """
            #JIANZHANG-BACKUP
            M99${'\u001F'}1${'\u001F'}9.0.0${'\u001F'}1
        """.trimIndent().toByteArray()
        val e = runCatching { BackupFormat.decode(future) }.exceptionOrNull()
        assertTrue(e is CorruptBackupException)
        assertTrue(e!!.message!!.contains("升级"))
    }

    @Test
    fun `缺 META 段要拒绝`() {
        val broken = """
            #JIANZHANG-BACKUP
        """.trimIndent().toByteArray()
        val e = runCatching { BackupFormat.decode(broken) }.exceptionOrNull()
        assertTrue(e is CorruptBackupException)
    }
}

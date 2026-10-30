package com.mizhii.ledger.domain.backup

import androidx.room.withTransaction
import com.mizhii.ledger.crypto.BackupCipher
import com.mizhii.ledger.crypto.CorruptBackupException
import com.mizhii.ledger.data.db.LedgerDatabase
import com.mizhii.ledger.data.prefs.SettingsRepository
import com.mizhii.ledger.util.Dates

/** 恢复时如何处理已有数据 */
enum class RestoreMode {
    /** 按主键合并：备份里有就覆盖，没有的保留。适合"救回误删" */
    MERGE,

    /** 清空后整体替换。适合"换机 / 重装后还原" */
    REPLACE,
}

/** 解密后的摘要，用于"确认要恢复吗"这一步 */
data class BackupSummary(
    val createdAt: Long,
    val appVersion: String,
    val schemaVersion: Int,
    val txnCount: Int,
    val categoryCount: Int,
    val ruleCount: Int,
    val ignoreCount: Int,
    val batchCount: Int,
) {
    val createdLabel: String get() = Dates.dayKey(createdAt) + " " + Dates.timeLabel(createdAt)
}

data class RestoreResult(
    val mode: RestoreMode,
    val txnAdded: Int,
    val txnUpdated: Int,
    val txnRemoved: Int,
    val categoryCount: Int,
    val ruleCount: Int,
    val ignoreCount: Int,
    val batchCount: Int,
)

/**
 * 备份与恢复（M5 §7.6）。
 *
 * 备份范围：流水 / 分类 / 规则 / 忽略名单 / 导入批次 / 少量设置。
 * **不含** `raw_capture` —— 那是 7 天过期的诊断日志，带上只会把文件撑大。
 *
 * 导入批次必须备份：否则换机恢复后再导入同一份账单，
 * 幂等校验（fileSha256）失效，整份账单会被重复记一遍。
 */
class BackupService(
    private val db: LedgerDatabase,
    private val settingsRepository: SettingsRepository,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 从数据库与设置里组装一份待加密的备份内容 */
    suspend fun buildContent(): BackupContent {
        val settings = settingsRepository.current()
        return BackupContent(
            formatVersion = BackupFormat.VERSION,
            schemaVersion = SCHEMA_VERSION,
            appVersion = appVersion,
            createdAt = now(),
            categories = db.categoryDao().getAllIncludingArchived(),
            rules = db.ruleDao().getAll(),
            txns = db.txnDao().getAll(),
            ignoreEntries = db.ignoreEntryDao().getAll(),
            importBatches = db.importBatchDao().getAll(),
            settings = linkedMapOf(
                KEY_THRESHOLD to settings.autoConfirmThreshold.toString(),
                KEY_DEDUPE to settings.dedupeWindowSeconds.toString(),
                KEY_MIN_AMOUNT to settings.minAmountCents.toString(),
                KEY_NOTIFY_ON_CAPTURE to settings.notifyOnCapture.toString(),
                KEY_NOTIF_CHANNEL to settings.notifChannelEnabled.toString(),
                KEY_A11Y_CHANNEL to settings.a11yChannelEnabled.toString(),
            ),
        )
    }

    /** 生成可以直接落盘的加密备份字节 */
    suspend fun exportBlob(password: CharArray): ByteArray {
        val content = buildContent()
        val plain = BackupFormat.encode(content)
        return BackupCipher.encrypt(password, plain)
    }

    /**
     * 解密并解析备份文件。
     *
     * PBKDF2 是 CPU 密集的（200k 次迭代约几百毫秒），调用方必须放到 IO 线程。
     */
    fun openBackup(password: CharArray, blob: ByteArray): BackupContent {
        val plain = BackupCipher.decrypt(password, blob)
        val content = BackupFormat.decode(plain)
        if (content.schemaVersion > SCHEMA_VERSION) {
            throw CorruptBackupException(
                "这份备份来自更新的 App（数据库版本 ${content.schemaVersion}，" +
                    "本机为 $SCHEMA_VERSION），请先升级 App 再恢复"
            )
        }
        return content
    }

    fun summarize(content: BackupContent): BackupSummary = BackupSummary(
        createdAt = content.createdAt,
        appVersion = content.appVersion,
        schemaVersion = content.schemaVersion,
        txnCount = content.txns.size,
        categoryCount = content.categories.size,
        ruleCount = content.rules.size,
        ignoreCount = content.ignoreEntries.size,
        batchCount = content.importBatches.size,
    )

    /**
     * 落库。整个过程在一个事务里：中途失败不会留下"删了一半"的残局。
     */
    suspend fun applyRestore(content: BackupContent, mode: RestoreMode): RestoreResult {
        val result = db.withTransaction {
            val existingIds = db.txnDao().getAll().mapTo(HashSet()) { it.id }
            val backupIds = content.txns.mapTo(HashSet()) { it.id }

            if (mode == RestoreMode.REPLACE) {
                db.txnDao().deleteAll()
                db.ignoreEntryDao().deleteAll()
                db.importBatchDao().deleteAll()
            }

            // 分类与规则不删：它们混着内置项，清空会让内置项消失（首次启动才会重建）
            db.categoryDao().restoreAll(content.categories)
            db.ruleDao().restoreAll(content.rules)
            db.txnDao().restoreAll(content.txns)
            db.ignoreEntryDao().restoreAll(content.ignoreEntries)
            db.importBatchDao().restoreAll(content.importBatches)

            RestoreResult(
                mode = mode,
                txnAdded = backupIds.count { it !in existingIds },
                txnUpdated = backupIds.count { it in existingIds },
                txnRemoved = if (mode == RestoreMode.REPLACE) existingIds.count { it !in backupIds } else 0,
                categoryCount = content.categories.size,
                ruleCount = content.rules.size,
                ignoreCount = content.ignoreEntries.size,
                batchCount = content.importBatches.size,
            )
        }
        applySettings(content.settings)
        return result
    }

    /**
     * 恢复设置。
     * DataStore 是独立于 Room 的存储，没法进上面那个事务；而且这些偏好丢了也不影响数据完整性，
     * 所以即使写失败也只是回到默认值，不阻断恢复。
     */
    private suspend fun applySettings(settings: Map<String, String>) {
        runCatching {
            settings[KEY_THRESHOLD]?.toIntOrNull()?.let { settingsRepository.setAutoConfirmThreshold(it) }
            settings[KEY_DEDUPE]?.toIntOrNull()?.let { settingsRepository.setDedupeWindowSeconds(it) }
            settings[KEY_MIN_AMOUNT]?.toLongOrNull()?.let { settingsRepository.setMinAmountCents(it) }
            settings[KEY_NOTIFY_ON_CAPTURE]?.toBooleanStrictOrNull()
                ?.let { settingsRepository.setNotifyOnCapture(it) }
            settings[KEY_NOTIF_CHANNEL]?.toBooleanStrictOrNull()
                ?.let { settingsRepository.setNotifChannelEnabled(it) }
            settings[KEY_A11Y_CHANNEL]?.toBooleanStrictOrNull()
                ?.let { settingsRepository.setA11yChannelEnabled(it) }
        }
    }

    companion object {
        /** 与 LedgerDatabase 的 version 保持一致 */
        const val SCHEMA_VERSION: Int = 1

        private const val KEY_THRESHOLD = "autoConfirmThreshold"
        private const val KEY_DEDUPE = "dedupeWindowSeconds"
        private const val KEY_MIN_AMOUNT = "minAmountCents"
        private const val KEY_NOTIFY_ON_CAPTURE = "notifyOnCapture"
        private const val KEY_NOTIF_CHANNEL = "notifChannelEnabled"
        private const val KEY_A11Y_CHANNEL = "a11yChannelEnabled"
    }
}

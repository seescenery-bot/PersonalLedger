package com.mizhii.ledger.di

import android.content.Context
import com.mizhii.ledger.data.db.DatabaseProvider
import com.mizhii.ledger.data.db.entity.TxnEntity
import com.mizhii.ledger.data.db.entity.TxnStatus
import com.mizhii.ledger.data.prefs.SettingsRepository
import com.mizhii.ledger.data.repo.CategoryRepository
import com.mizhii.ledger.data.repo.RawCaptureRepository
import com.mizhii.ledger.data.repo.RuleRepository
import com.mizhii.ledger.data.repo.TxnRepository
import com.mizhii.ledger.domain.capture.CapturePipeline
import com.mizhii.ledger.domain.capture.Categorizer
import com.mizhii.ledger.domain.capture.Deduper
import com.mizhii.ledger.domain.backup.BackupService
import com.mizhii.ledger.domain.import.BillImporter
import com.mizhii.ledger.notify.CaptureNotifier
import com.mizhii.ledger.widget.LedgerWidgetProvider

/**
 * 手写依赖容器（文档 §9.5 的降级方案）。
 *
 * 依赖关系仍然可控：Database → DAO → Repository/Pipeline → ViewModel。
 * 若后续要上 Hilt，删掉本文件改由 Module 提供即可。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val database = DatabaseProvider.get(appContext)

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val txnRepository: TxnRepository by lazy { TxnRepository(database.txnDao()) }

    val categoryRepository: CategoryRepository by lazy { CategoryRepository(database.categoryDao()) }

    val ruleRepository: RuleRepository by lazy { RuleRepository(database.ruleDao()) }

    val rawCaptureRepository: RawCaptureRepository by lazy {
        RawCaptureRepository(database.rawCaptureDao())
    }

    /** 记账通知 */
    val captureNotifier: CaptureNotifier by lazy { CaptureNotifier(appContext) }

    /** 备份与恢复（M5 §7.6） */
    val backupService: BackupService by lazy {
        BackupService(
            db = database,
            settingsRepository = settingsRepository,
            appVersion = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            }.getOrNull() ?: "unknown"
        )
    }

    /** 捕获管线：NotificationListenerService 里直接用 */
    val capturePipeline: CapturePipeline by lazy {
        CapturePipeline(
            txnDao = database.txnDao(),
            rawCaptureDao = database.rawCaptureDao(),
            ignoreEntryDao = database.ignoreEntryDao(),
            deduper = Deduper(database.txnDao()),
            categorizer = Categorizer(database.ruleDao()),
            settingsRepository = settingsRepository,
            onNewTxn = { entity -> notifyNewTxn(entity) }
        )
    }

    /** M4 账单导入：微信 xlsx / 微信&支付宝 csv */
    val billImporter: BillImporter by lazy {
        BillImporter(
            txnDao = database.txnDao(),
            importBatchDao = database.importBatchDao(),
            categorizer = Categorizer(database.ruleDao()),
            settingsRepository = settingsRepository,
            deduper = Deduper(database.txnDao()),
            onImported = { result -> notifyImport(result) }
        )
    }

    /**
     * 新建一笔账 → 刷新桌面小部件 + 推送通知。
     * 只在真正新增时调用（合并/去重不打扰）；通知失败绝不影响已入库的数据。
     */
    private suspend fun notifyNewTxn(entity: TxnEntity) {
        // 小部件刷新与"是否推送通知"无关：用户关了通知，桌面上的数字照样要更新
        LedgerWidgetProvider.refresh(appContext)
        runCatching {
            if (!settingsRepository.current().notifyOnCapture) return
            captureNotifier.ensureChannel()
            val categoryName = entity.categoryId?.let {
                runCatching { database.categoryDao().findById(it)?.name }.getOrNull()
            }
            captureNotifier.notifyRecorded(
                CaptureNotifier.RecordedTxn(
                    txnId = entity.id,
                    amountCents = entity.amountCents,
                    direction = entity.direction,
                    counterparty = entity.counterparty,
                    categoryName = categoryName,
                    pending = entity.status == TxnStatus.PENDING,
                    channel = entity.channel
                )
            )
        }
    }

    /** 账单导入是批量操作，只推一条汇总，绝不逐笔推 */
    private suspend fun notifyImport(result: BillImporter.Result) {
        LedgerWidgetProvider.refresh(appContext)
        runCatching {
            if (!settingsRepository.current().notifyOnCapture) return
            captureNotifier.notifyImport(result.fileName, result.newCount, result.mergedCount)
        }
    }
}

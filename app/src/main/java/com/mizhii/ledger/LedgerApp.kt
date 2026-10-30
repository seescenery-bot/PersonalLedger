package com.mizhii.ledger

import android.app.Application
import com.mizhii.ledger.data.db.BuiltinData
import com.mizhii.ledger.data.db.DatabaseProvider
import com.mizhii.ledger.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LedgerApp : Application() {

    /** 手写依赖容器（§9.5）。所有 ViewModel 通过它取仓库。 */
    val container: AppContainer by lazy { AppContainer(this) }

    /** 应用级协程域：捕获管线、清理任务等长生命周期工作都在这里跑（§9.4） */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        bootstrapDatabase()
    }

    private fun bootstrapDatabase() {
        appScope.launch {
            // 任何初始化失败都不能让 App 崩溃：捕获链路与数据库都必须"降级可用"
            runCatching {
                val db = DatabaseProvider.get(this@LedgerApp)

                if (db.categoryDao().count() == 0) {
                    db.categoryDao().upsertAll(BuiltinData.categories)
                }
                if (db.ruleDao().count() == 0) {
                    db.ruleDao().upsertAll(BuiltinData.rules)
                }

                // §8.5：原始捕获日志只保留 7 天
                val cutoff = System.currentTimeMillis() - RAW_CAPTURE_RETENTION_DAYS * 24L * 3600L * 1000L
                db.rawCaptureDao().deleteOlderThan(cutoff)

                // 修复历史脏数据：无障碍通道曾把 uptimeMillis 当 epoch，账目落到 1970 年
                runCatching { db.txnDao().repairImplausibleDates(IMPLAUSIBLE_BEFORE_MS, System.currentTimeMillis()) }
            }
        }
    }

    private companion object {
        const val RAW_CAPTURE_RETENTION_DAYS = 7L

        /** 2020-01-01 的时间戳：早于此的 occurredAt 一定是脏数据（本项目 2026 年才投入使用） */
        const val IMPLAUSIBLE_BEFORE_MS = 1_577_836_800_000L
    }
}

package com.mizhii.ledger.domain.import

import com.mizhii.ledger.data.db.dao.ImportBatchDao
import com.mizhii.ledger.data.db.dao.TxnDao
import com.mizhii.ledger.data.db.entity.ImportBatchEntity
import com.mizhii.ledger.data.db.entity.TxnEntity
import com.mizhii.ledger.data.db.entity.TxnStatus
import com.mizhii.ledger.data.import.BillFile
import com.mizhii.ledger.data.import.toCandidate
import com.mizhii.ledger.data.prefs.SettingsRepository
import com.mizhii.ledger.domain.capture.Categorizer
import com.mizhii.ledger.domain.capture.ConfidenceScorer
import com.mizhii.ledger.domain.capture.Deduper
import com.mizhii.ledger.domain.model.TxnCandidate
import com.mizhii.ledger.util.Dates
import com.mizhii.ledger.util.Hashing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID

/**
 * 账单导入（需求文档 §7.9，M4）。
 *
 * 分两步，因为 UI 需要"先预览再确认"：
 *   1. [preview] 纯解析，不碰数据库 —— 用户能先看到「53 笔 / 收入 11 笔 ¥3849.69 / 支出 39 笔 ¥496.47」
 *      以及**对账结果**（账单声明的汇总 vs 实际解析），确认无误再导入
 *   2. [import] 落库：逐笔去重、分类、评分，最后写一条导入批次
 *
 * 三个关键决策：
 *  - **账单是权威数据源**（`source = CSV`）：状态直接 CONFIRMED，不走置信度阈值；
 *    唯一例外是"没有对方名的收入"，仍强制待确认（与通道 A/B 口径一致）
 *  - **中性交易不入账**：零钱提现这类 `收/支 = /` 的行单独计数并跳过，
 *    它们是自己的钱搬家，记进收支会同时污染收入和支出
 *  - **同一文件重复导入直接拒绝**：按文件 sha256 查导入批次
 */
class BillImporter(
    private val txnDao: TxnDao,
    private val importBatchDao: ImportBatchDao,
    private val categorizer: Categorizer,
    private val settingsRepository: SettingsRepository,
    private val deduper: Deduper,
    /** 导入完成后的回调（用于推送汇总通知）；保持 domain 层不依赖 Android 通知 API */
    private val onImported: (suspend (Result) -> Unit)? = null,
) {

    private val mutex = Mutex()

    data class Preview(
        val fileName: String,
        val sha256: String,
        val parsed: BillFile.Parsed,
        /** 非空表示这个文件之前导入过 */
        val alreadyImported: ImportBatchEntity?,
    ) {
        val reconcileProblems: List<String> get() = parsed.reconcileProblems()
    }

    data class Result(
        val fileName: String,
        val kind: BillFile.Kind,
        val encoding: String,
        val rowTotal: Int,
        val newCount: Int,
        val mergedCount: Int,
        val neutralCount: Int,
        val reconcile: BillFile.Reconcile,
        val problems: List<String>,
    )

    /** 纯解析 + 幂等校验，不写库。解析失败会抛 [IllegalArgumentException]（消息可直接展示给用户）。 */
    suspend fun preview(fileName: String, bytes: ByteArray): Preview {
        val table = BillFile.readTable(fileName, bytes)
        val parsed = BillFile.parse(table)
        val sha = sha256Hex(bytes)
        return Preview(
            fileName = fileName,
            sha256 = sha,
            parsed = parsed,
            alreadyImported = runCatching { importBatchDao.findBySha256(sha) }.getOrNull(),
        )
    }

    suspend fun import(preview: Preview): Result = mutex.withLock {
        if (preview.alreadyImported != null) {
            throw IllegalStateException("这个文件已经导入过（${preview.alreadyImported.fileName}），不能重复导入")
        }

        val settings = runCatching { settingsRepository.current() }.getOrDefault(
            com.mizhii.ledger.data.prefs.LedgerSettings()
        )
        val channel = preview.parsed.channel
        var newCount = 0
        var mergedCount = 0
        val now = System.currentTimeMillis()

        for (entry in preview.parsed.entries) {
            val candidate = entry.toCandidate(channel)

            val existing = deduper.findMatch(candidate, settings.dedupeWindowSeconds.toLong())
            if (existing != null) {
                val hasRule = existing.categoryId != null
                val mergedScore = ConfidenceScorer.score(
                    candidate = candidate,
                    hasClearSemantic = true,
                    hasRuleMatch = hasRule,
                    merged = true
                )
                txnDao.update(deduper.merge(existing, candidate, mergedScore))
                mergedCount++
                continue
            }

            val category = categorizer.categorize(candidate)
            val score = ConfidenceScorer.score(
                candidate = candidate,
                hasClearSemantic = true,
                hasRuleMatch = category.matchedRuleId != null
            )
            val entity = TxnEntity(
                occurredAt = candidate.occurredAt,
                dayKey = Dates.dayKey(candidate.occurredAt),
                amountCents = candidate.amountCents,
                direction = candidate.direction,
                channel = candidate.channel,
                counterparty = candidate.counterparty,
                goods = candidate.goods,
                externalNo = candidate.externalNo,
                merchantNo = candidate.merchantNo,
                payMethod = candidate.payMethod,
                categoryId = category.categoryId,
                categorySource = category.source,
                source = candidate.source,
                // 账单是权威数据，分数只作记录
                confidence = maxOf(score, 90),
                status = if (candidate.forcePending) TxnStatus.PENDING else TxnStatus.CONFIRMED,
                timePrecision = candidate.timePrecision,
                rawText = candidate.rawText,
                rowHash = rowHashOf(candidate),
                createdAt = now,
                updatedAt = now
            )
            runCatching { txnDao.insert(entity) }
                .onSuccess { newCount++ }
                .onFailure { mergedCount++ } // 唯一索引冲突 = 已存在
        }

        val reconcile = preview.parsed.computed()
        importBatchDao.insert(
            ImportBatchEntity(
                id = UUID.randomUUID().toString(),
                channel = channel,
                fileName = preview.fileName,
                fileSha256 = preview.sha256,
                encoding = preview.parsed.encoding,
                rowCount = reconcile.totalCount,
                newCount = newCount,
                mergedCount = mergedCount,
                skippedCount = preview.parsed.neutral.size,
                importedAt = now
            )
        )

        val result = Result(
            fileName = preview.fileName,
            kind = preview.parsed.kind,
            encoding = preview.parsed.encoding,
            rowTotal = reconcile.totalCount,
            newCount = newCount,
            mergedCount = mergedCount,
            neutralCount = preview.parsed.neutral.size,
            reconcile = reconcile,
            problems = preview.reconcileProblems,
        )
        // 导入完成通知（批量操作只推汇总）；失败不影响已入库数据
        runCatching { onImported?.invoke(result) }
        return result
    }

    private fun rowHashOf(candidate: TxnCandidate): String {
        val key = candidate.externalNo
            ?: "${candidate.occurredAt}|${candidate.amountCents}|${candidate.direction}|${candidate.counterparty}"
        return Hashing.sha256("${candidate.channel}|$key")
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

package com.mizhii.ledger.domain.capture

import com.mizhii.ledger.data.db.dao.IgnoreEntryDao
import com.mizhii.ledger.data.db.dao.RawCaptureDao
import com.mizhii.ledger.data.db.dao.TxnDao
import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.RawCaptureEntity
import com.mizhii.ledger.data.db.entity.TxnEntity
import com.mizhii.ledger.data.db.entity.TxnSource
import com.mizhii.ledger.data.db.entity.TxnStatus
import com.mizhii.ledger.data.prefs.LedgerSettings
import com.mizhii.ledger.data.prefs.SettingsRepository
import com.mizhii.ledger.domain.model.DiscardReason
import com.mizhii.ledger.domain.model.ParseOutcome
import com.mizhii.ledger.domain.model.TxnCandidate
import com.mizhii.ledger.util.Dates
import com.mizhii.ledger.util.Hashing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 捕获管线（需求文档 §6 的数据流图）：
 *
 *   归一化 → 去重 → 分类 → 置信度评分 → 落库(CONFIRMED/PENDING) → 原始日志
 *
 * 并发安全：通道 A 与通道 B 可能同时命中同一笔，「查重 → 插入」必须整体加锁（§6.5.5）。
 * 任何异常都不允许逃逸出去把监听服务带崩（§0 铁律 2）。
 */
class CapturePipeline(
    private val txnDao: TxnDao,
    private val rawCaptureDao: RawCaptureDao,
    private val ignoreEntryDao: IgnoreEntryDao,
    private val deduper: Deduper,
    private val categorizer: Categorizer,
    private val settingsRepository: SettingsRepository,
    /**
     * 新建一笔账后的回调（用于推送记账通知）。
     * 只在**真正新增**时触发：通道 A/B 同时命中同一笔时第二条会走合并分支，不再重复提醒。
     * 刻意做成回调而不是直接依赖 Android 通知 API —— 管线要保持纯逻辑、可单测。
     */
    private val onNewTxn: (suspend (TxnEntity) -> Unit)? = null
) {

    private val mutex = Mutex()

    private companion object {
        /** 2020-01-01：早于它的时间戳不可能是真实的记账时间 */
        const val MIN_PLAUSIBLE_TIME_MS = 1_577_836_800_000L
    }

    suspend fun handleNotification(
        packageName: String,
        title: String?,
        body: String?,
        postTime: Long
    ) {
        val settings = runCatching { settingsRepository.current() }.getOrDefault(LedgerSettings())
        if (!settings.notifChannelEnabled) return

        try {
            when (
                val outcome = NotificationParser.parse(
                    packageName = packageName,
                    title = title,
                    body = body,
                    postTime = postTime,
                    acceptAnyPackage = settings.debugAcceptAllPackages
                )
            ) {
                is ParseOutcome.Discarded ->
                    logDiscard(packageName, outcome.reason, outcome.normalizedText)

                is ParseOutcome.Success ->
                    persist(packageName, outcome.candidate, settings)
            }
        } catch (t: Throwable) {
            // 兜底：解析/落库任何异常都不能让监听服务崩溃
            runCatching { logDiscard(packageName, DiscardReason.EXCEPTION, body.orEmpty()) }
        }
    }

    /**
     * 通道 B：无障碍读屏（§6.3）。
     * 与通知通道共用同一条去重/分类/评分/落库管线，因此两路同时命中同一笔时会自动合并。
     */
    suspend fun handleAccessibility(packageName: String, texts: List<String>, postTime: Long) {
        val settings = runCatching { settingsRepository.current() }.getOrDefault(LedgerSettings())
        if (!settings.a11yChannelEnabled) return

        // 兜底：postTime 必须是 epoch 毫秒。调用方曾误传 AccessibilityEvent.eventTime
        // （uptimeMillis）导致账目落到 1970 年，且完全不报错 —— 这类"静默记错年月"的
        // 错误比崩溃更难发现，所以在这里再挡一次。
        val safePostTime = if (postTime < MIN_PLAUSIBLE_TIME_MS) System.currentTimeMillis() else postTime

        try {
            when (
                val outcome = A11yPageParser.parse(
                    A11yPageInput(packageName = packageName, texts = texts, postTime = safePostTime)
                )
            ) {
                is ParseOutcome.Discarded ->
                    logDiscard(
                        packageName = packageName,
                        reason = outcome.reason,
                        text = outcome.normalizedText,
                        source = TxnSource.ACCESSIBILITY
                    )

                is ParseOutcome.Success ->
                    persist(packageName, outcome.candidate, settings)
            }
        } catch (t: Throwable) {
            runCatching {
                logDiscard(
                    packageName = packageName,
                    reason = DiscardReason.EXCEPTION,
                    text = texts.joinToString(" | ").take(1000),
                    source = TxnSource.ACCESSIBILITY
                )
            }
        }
    }

    private suspend fun persist(
        packageName: String,
        candidate: TxnCandidate,
        settings: LedgerSettings
    ) = mutex.withLock {
        if (candidate.amountCents < settings.minAmountCents) {
            logDiscard(packageName, DiscardReason.TOO_SMALL, candidate.rawText.orEmpty(), candidate.source)
            return@withLock
        }

        val externalNo = candidate.externalNo
        if (!externalNo.isNullOrBlank() && ignoreEntryDao.contains(externalNo) > 0) {
            logDiscard(packageName, DiscardReason.IGNORED, candidate.rawText.orEmpty(), candidate.source)
            return@withLock
        }

        val category = categorizer.categorize(candidate)
        val hasRule = category.matchedRuleId != null
        val baseScore = ConfidenceScorer.score(
            candidate = candidate,
            hasClearSemantic = true,
            hasRuleMatch = hasRule
        )

        // ── 去重：命中则合并，不新增 ──
        val existing = deduper.findMatch(candidate, settings.dedupeWindowSeconds.toLong())
        if (existing != null) {
            val mergedScore = ConfidenceScorer.score(
                candidate = candidate,
                hasClearSemantic = true,
                hasRuleMatch = hasRule,
                merged = true
            )
            txnDao.update(deduper.merge(existing, candidate, mergedScore))
            // 按候选的真实来源记日志：默认值会把无障碍成功记成 NOTIFICATION，
            // 导致诊断面板「最近采集」里看不出是哪个通道抓到的（真机踩过）
            logSuccess(packageName, candidate, existing.id, candidate.source)
            return@withLock
        }

        if (ConfidenceScorer.shouldDiscard(baseScore)) {
            logDiscard(packageName, DiscardReason.LOW_CONFIDENCE, candidate.rawText.orEmpty(), candidate.source)
            return@withLock
        }

        val now = System.currentTimeMillis()
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
            confidence = baseScore,
            // forcePending 优先于分数：无付款方的收入（收款码到账等）不得自动入账
            status = if (candidate.forcePending) {
                TxnStatus.PENDING
            } else if (ConfidenceScorer.isConfirmed(baseScore, settings.autoConfirmThreshold)) {
                TxnStatus.CONFIRMED
            } else {
                TxnStatus.PENDING
            },
            timePrecision = candidate.timePrecision,
            rawText = candidate.rawText,
            rowHash = rowHashOf(candidate),
            createdAt = now,
            updatedAt = now
        )

        try {
            val id = txnDao.insert(entity)
            logSuccess(packageName, candidate, id, candidate.source)
            // 推送记账提醒；通知失败绝不影响已入库的账目
            runCatching { onNewTxn?.invoke(entity.copy(id = id)) }
        } catch (t: Throwable) {
            // rowHash / externalNo 唯一索引冲突 = 已经存在，按重复处理
            logDiscard(packageName, DiscardReason.DUPLICATE, candidate.rawText.orEmpty(), candidate.source)
        }
    }

    private fun rowHashOf(candidate: TxnCandidate): String {
        val key = candidate.externalNo
            ?: "${candidate.occurredAt}|${candidate.amountCents}|${candidate.direction}|${candidate.counterparty}"
        return Hashing.sha256("${candidate.channel}|$key")
    }

    private suspend fun logSuccess(
        packageName: String,
        candidate: TxnCandidate,
        txnId: Long,
        source: TxnSource = TxnSource.NOTIFICATION
    ) {
        log(
            packageName = packageName,
            text = candidate.rawText.orEmpty(),
            parsedOk = true,
            reason = null,
            txnId = txnId,
            source = source
        )
    }

    private suspend fun logDiscard(
        packageName: String,
        reason: String,
        text: String,
        source: TxnSource = TxnSource.NOTIFICATION
    ) {
        log(
            packageName = packageName,
            text = text,
            parsedOk = false,
            reason = reason,
            txnId = null,
            source = source
        )
    }

    private suspend fun log(
        packageName: String,
        text: String,
        parsedOk: Boolean,
        reason: String?,
        txnId: Long?,
        source: TxnSource
    ) {
        runCatching {
            rawCaptureDao.insert(
                RawCaptureEntity(
                    packageName = packageName,
                    channel = NotificationParser.channelOf(packageName) ?: Channel.OTHER,
                    source = source,
                    rawText = text.take(2000),
                    capturedAt = System.currentTimeMillis(),
                    dedupeHash = Hashing.sha256("$packageName|$text").take(32),
                    parsedOk = parsedOk,
                    discardReason = reason,
                    txnId = txnId
                )
            )
        }
    }
}

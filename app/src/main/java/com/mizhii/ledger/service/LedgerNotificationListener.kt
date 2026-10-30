package com.mizhii.ledger.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.mizhii.ledger.LedgerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 通道 A：通知监听（需求文档 §6.2）。
 *
 * 铁律：
 *  - `onNotificationPosted` 里**不做解析、不写数据库**，只把事件丢进协程就返回；
 *  - 任何异常都必须在本服务内被吞掉，服务崩溃会被系统永久禁用（§0 铁律 2）。
 *
 * 保活（小米14 / 澎湃OS 3 实测）：
 *  系统把本服务当作"绑定服务"托管，进程被杀后会立刻重新拉起（实测 force-stop 后约 2s 重生，
 *  `am kill` 更是根本不杀 —— 进程处于 PERCEPTIBLE，adj=200）。但"重生"和"收到通知"之间有一个空洞：
 *  进程死着的时候发来的通知，系统不会补投。所以要主动补：
 *   1. [onListenerConnected] 里用 `getActiveNotifications()` 把仍然挂在通知栏里的通知重放一遍。
 *      已入账的会被去重逻辑识别（精确单号 + 模糊窗口），不会重复记账。
 *      实测：force-stop 后立刻投递的 ¥64.44，在没有这段逻辑时彻底丢失。
 *   2. [onListenerDisconnected] 里调用 `requestRebind()` 自愈 —— 系统/省电策略解绑后能自己回来。
 */
class LedgerNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 同一通知可能被多次回调（微信/支付宝会更新同一条），按 key + postTime 去抖 */
    private val recentKeys = LinkedHashMap<String, Long>()
    private val recentLock = Any()

    private var lastRebindRequestAt = 0L

    private fun app(): LedgerApp? = applicationContext as? LedgerApp

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            scope.launch { app()?.container?.settingsRepository?.markNotifConnected() }
        }
        recoverActiveNotifications()
    }

    override fun onListenerDisconnected() {
        // 解绑后由我们请求重连，否则通知通道就静默死掉了。
        // 加频率限制，避免系统一直拒绝时我们疯狂重试。
        runCatching {
            val now = System.currentTimeMillis()
            if (now - lastRebindRequestAt > REBIND_MIN_INTERVAL_MS) {
                lastRebindRequestAt = now
                requestRebind(ComponentName(this, LedgerNotificationListener::class.java))
                Log.i(TAG, "requestRebind after listener disconnected")
            }
        }
        runCatching { scope.cancel() }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        handle(sbn ?: return)
    }

    /**
     * 把断连期间漏掉的通知捞回来。
     *
     * 只处理仍然挂在通知栏里、且投递时间在窗口内的通知：
     *  - 太老的不碰（进程可能死了一整夜，回放半小时前的通知只会污染账本）；
     *  - `postTime` 在未来的不碰（时钟回拨会得到负数）。
     */
    private fun recoverActiveNotifications() {
        try {
            val actives = activeNotifications ?: return
            if (actives.isEmpty()) return
            val now = System.currentTimeMillis()
            var replayed = 0
            for (sbn in actives) {
                val age = now - sbn.postTime
                if (age < 0 || age > RECOVERY_WINDOW_MS) continue
                if (handle(sbn)) replayed++
            }
            if (replayed > 0) Log.i(TAG, "replayed $replayed active notification(s)")
        } catch (t: Throwable) {
            Log.w(TAG, "recoverActiveNotifications failed: ${t.message}")
        }
    }

    /** @return 是否真的把这个通知交给了管线（用于日志统计） */
    private fun handle(posted: StatusBarNotification): Boolean {
        try {
            if (!shouldHandle(posted.key, posted.postTime)) return false

            val notification = posted.notification ?: return false
            val extras = notification.extras ?: return false
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val body = extractBody(notification)
            if (title.isNullOrBlank() && body.isBlank()) return false

            val container = app()?.container ?: return false
            scope.launch {
                runCatching {
                    container.capturePipeline.handleNotification(
                        packageName = posted.packageName,
                        title = title,
                        body = body,
                        postTime = posted.postTime
                    )
                }
            }
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "handle failed: ${t.message}")
            return false
        }
    }

    /**
     * 按文档 §6.2.3 的优先级取正文。
     * 真机实测：微信支付通知只提供 EXTRA_TEXT，没有 BIG_TEXT / TEXT_LINES（§6.2.5）。
     */
    private fun extractBody(notification: Notification): String {
        val extras = notification.extras ?: return ""
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }
            ?.takeIf { it.isNotBlank() }?.let { return it }
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    private fun shouldHandle(key: String, postTime: Long): Boolean {
        synchronized(recentLock) {
            val last = recentKeys[key]
            if (last != null && last == postTime) return false
            recentKeys[key] = postTime
            if (recentKeys.size > 64) {
                val oldest = recentKeys.keys.firstOrNull()
                if (oldest != null) recentKeys.remove(oldest)
            }
            return true
        }
    }

    override fun onDestroy() {
        runCatching { scope.cancel() }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "LedgerNotifListener"

        /** 断连恢复窗口：只补最近 30 分钟还挂在通知栏的通知 */
        const val RECOVERY_WINDOW_MS = 30L * 60L * 1000L

        /** requestRebind 的最小间隔，避免死循环重试 */
        const val REBIND_MIN_INTERVAL_MS = 60L * 1000L
    }
}

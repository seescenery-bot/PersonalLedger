package com.mizhii.ledger.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mizhii.ledger.MainActivity
import com.mizhii.ledger.R
import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.util.Money

/**
 * 记账通知（本次新增功能）。
 *
 * 设计取舍：
 *  - **只在"新建一笔"时推，合并/去重时不推**。微信支付会同时来「通知」和「支付成功页」，
 *    两条都会进管线，但第一笔建完就通知一次，第二条合并时静默 —— 一次支付只打扰一次。
 *  - 待确认的账目带「确认 / 忽略」两个动作，用户可以在通知栏直接了结，不用进 app。
 *  - 通知 id 用 txnId，这样同一笔的重复通知会互相覆盖，不会堆一屏。
 *  - 全程 try-catch：通知失败绝不能影响记账本身。
 */
class CaptureNotifier(private val context: Context) {

    data class RecordedTxn(
        val txnId: Long,
        val amountCents: Long,
        val direction: Direction,
        val counterparty: String?,
        val categoryName: String?,
        val pending: Boolean,
        val channel: Channel,
    )

    fun ensureChannel() {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "记账提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "自动记账后提醒；待确认的账目可直接在通知里确认或忽略"
                    setShowBadge(true)
                    enableVibration(false)
                    enableLights(false)
                }
            )
        }
    }

    fun notifyRecorded(txn: RecordedTxn) {
        if (!notificationsAllowed()) return
        runCatching {
            ensureChannel()
            val sign = if (txn.direction == Direction.EXPENSE) "-" else "+"
            val title = "已记账 $sign${Money.SYMBOL}${Money.format(txn.amountCents)}"

            val parts = buildList {
                txn.counterparty?.takeIf { it.isNotBlank() }?.let { add(it) }
                txn.categoryName?.takeIf { it.isNotBlank() }?.let { add(it) }
                add(if (txn.pending) "待确认" else "已记录")
            }
            val text = parts.joinToString(" · ")

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_ledger)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent(openPending = txn.pending))

            if (txn.pending) {
                builder.addAction(
                    0,
                    "确认",
                    PendingActionReceiver.pendingIntent(
                        context, PendingActionReceiver.ACTION_CONFIRM, txn.txnId
                    )
                )
                builder.addAction(
                    0,
                    "忽略",
                    PendingActionReceiver.pendingIntent(
                        context, PendingActionReceiver.ACTION_IGNORE, txn.txnId
                    )
                )
            }

            post(txn.txnId.toInt(), builder.build())
        }
    }

    /** 账单导入完成后的汇总通知（导入几十上百笔，绝不逐笔推） */
    fun notifyImport(fileName: String, newCount: Int, mergedCount: Int) {
        if (!notificationsAllowed()) return
        runCatching {
            ensureChannel()
            val title = "账单导入完成"
            val text = "新增 $newCount 笔" + if (mergedCount > 0) "，合并 $mergedCount 笔" else ""
            post(
                IMPORT_NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_ledger)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText("$text\n$fileName")
                    )
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent(openPending = false))
                    .build()
            )
        }
    }

    fun cancel(txnId: Long) {
        runCatching { NotificationManagerCompat.from(context).cancel(txnId.toInt()) }
    }

    /**
     * 能不能发通知。
     *
     * 两层判断缺一不可：
     *  - `areNotificationsEnabled()` 反映"通知总开关 + 本渠道是否被关"，澎湃OS 上用户可以在
     *    通知管理里单独干掉这个渠道，此时 POST_NOTIFICATIONS 仍是 GRANTED；
     *  - `POST_NOTIFICATIONS` 是 Android 13+ 的运行时权限，发通知前必须显式查。
     *
     * 不发通知不是错误：记账本身照做，只是少一次提醒。
     */
    private fun notificationsAllowed(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /**
     * 唯一出口。
     * `@SuppressLint("MissingPermission")` 是准确的：调用方已经过 [notificationsAllowed] 的门，
     * 而且权限随时可能被用户撤销 —— 所以这里还必须吞掉 SecurityException。
     */
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun openAppIntent(openPending: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (openPending) putExtra(MainActivity.EXTRA_OPEN_PENDING, true)
        }
        return PendingIntent.getActivity(
            context,
            if (openPending) 1 else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "capture_reminder"
        private const val IMPORT_NOTIFICATION_ID = 90_001
    }
}

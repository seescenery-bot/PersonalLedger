package com.mizhii.ledger.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mizhii.ledger.LedgerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知栏上的「确认 / 忽略」动作。
 *
 * 这是记账通知真正的价值：待确认的账目（置信度不够、收入无付款方）不用打开 app，
 * 在通知栏两下就了结。处理完顺手把通知撤掉。
 */
class PendingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val txnId = intent.getLongExtra(EXTRA_TXN_ID, -1L)
        if (txnId <= 0L) return

        // goAsync 让广播在协程结束后再 finish，避免 ANR
        val pendingResult = goAsync()
        val app = context.applicationContext as? LedgerApp
        if (app == null) {
            pendingResult.finish()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_CONFIRM -> app.container.txnRepository.confirm(txnId)
                    ACTION_IGNORE -> app.container.txnRepository.ignore(txnId)
                }
                CaptureNotifier(context).cancel(txnId)
            } catch (t: Throwable) {
                // 通知动作失败不能崩：用户还能在 app 里处理
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_CONFIRM = "com.mizhii.ledger.action.CONFIRM_TXN"
        const val ACTION_IGNORE = "com.mizhii.ledger.action.IGNORE_TXN"
        const val EXTRA_TXN_ID = "txn_id"

        fun pendingIntent(context: Context, action: String, txnId: Long): PendingIntent {
            val intent = Intent(context, PendingActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_TXN_ID, txnId)
            }
            return PendingIntent.getBroadcast(
                context,
                (action.hashCode() * 31 + txnId.toInt()),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}

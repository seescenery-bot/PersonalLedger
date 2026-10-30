package com.mizhii.ledger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mizhii.ledger.ui.LedgerRoot
import com.mizhii.ledger.ui.Routes
import com.mizhii.ledger.ui.theme.LedgerTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class MainActivity : ComponentActivity() {

    /**
     * 「点了记账通知」的事件流。
     *
     * 为什么不能在 onCreate 里读一次 intent 就完事：
     * 通知的 PendingIntent 带 `FLAG_ACTIVITY_SINGLE_TOP`，App 已经在运行时**不会重建 Activity**，
     * 只会走 [onNewIntent]。旧实现没有覆写它 —— App 在后台时点通知，什么都不发生。
     */
    private val openPendingEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 提前建好通知渠道：首次记账时才有渠道可发
        runCatching { (application as? LedgerApp)?.container?.captureNotifier?.ensureChannel() }

        val startRoute = if (intent?.getBooleanExtra(EXTRA_OPEN_PENDING, false) == true) {
            Routes.PENDING
        } else {
            Routes.TXN
        }

        setContent {
            LedgerTheme {
                LedgerRoot(startRoute = startRoute, openPendingEvents = openPendingEvents)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 更新 Activity 持有的 intent，避免旋转/重建后又按旧 intent 算起始页
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PENDING, false)) {
            openPendingEvents.tryEmit(Unit)
        }
    }

    companion object {
        const val EXTRA_OPEN_PENDING = "open_pending"
    }
}

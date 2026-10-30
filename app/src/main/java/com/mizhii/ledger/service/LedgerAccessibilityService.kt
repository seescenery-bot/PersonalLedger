package com.mizhii.ledger.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mizhii.ledger.LedgerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 通道 B：无障碍读屏（需求文档 §6.3）。
 *
 * 铁律：
 *  - **只读屏幕文本，绝不执行点击/输入/滑动**（§0 铁律 7）；
 *  - 事件整体包 try-catch，异常绝不逃逸——无障碍服务崩溃会被系统禁用；
 *  - 遍历有节点数与文本数上限，避免卡顿/OOM（§6.3.6）；
 *  - 高频的 TYPE_WINDOW_CONTENT_CHANGED 必须节流，否则会明显掉帧耗电。
 */
class LedgerAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var lastPackage: String? = null
    private var lastWindowId: Int = -1
    private var lastHandledAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            scope.launch {
                (applicationContext as? LedgerApp)?.container?.settingsRepository
                    ?.markA11yConnected()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            val e = event ?: return
            val pkg = e.packageName?.toString() ?: return
            if (pkg !in WATCHED_PACKAGES) return

            val type = e.eventType
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) return

            // 节流：同一窗口 300ms 内只解析一次（CONTENT_CHANGED 会高频触发）
            val now = System.currentTimeMillis()
            if (e.windowId == lastWindowId && pkg == lastPackage && now - lastHandledAt < THROTTLE_MS) {
                return
            }
            lastWindowId = e.windowId
            lastPackage = pkg
            lastHandledAt = now

            val root = rootInActiveWindow ?: return
            // 以**实际读到的窗口**判定包名：窗口切换瞬间 event.packageName 已经是新 app，
            // 但 rootInActiveWindow 还是上一个窗口（真机实测抓到了任务切换器、
            // 桌面小组件被标成 com.tencent.mm）。内容与包名不一致就整条丢弃。
            val rootPkg = runCatching { root.packageName?.toString() }.getOrNull()
            val effectivePkg = if (rootPkg.isNullOrBlank()) pkg else rootPkg
            if (effectivePkg !in WATCHED_PACKAGES) return

            val texts = collectTexts(root)
            if (texts.isEmpty()) return

            val container = (applicationContext as? LedgerApp)?.container ?: return
            scope.launch {
                runCatching {
                    container.capturePipeline.handleAccessibility(
                        packageName = effectivePkg,
                        texts = texts,
                        // ⚠️ 必须传墙钟时间，不能用 e.eventTime！
                        // AccessibilityEvent.getEventTime() 返回的是 **uptimeMillis（开机以来毫秒）**，
                        // 当页面里没有「支付时间」可解析时，它会作为 occurredAt 兜底直接落库。
                        // 真机事故（2026-10-30 07:42 微信 ¥20.00）：账目被记成 1970-01-17，
                        // 用户在当月列表里完全看不到，看起来就像"没记上"。
                        postTime = now
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onAccessibilityEvent failed: ${t.message}")
        }
    }

    /**
     * 广度优先收集文本，带节点数与文本数上限。
     * 不调用 `recycle()`：API 33+ 已废弃且为 no-op，minSdk 34 下调用只会产生编译警告。
     */
    private fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>(48)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES && out.size < MAX_TEXTS) {
            val node = queue.removeFirst()
            visited++

            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (out.size < MAX_TEXTS) out.add(it.take(MAX_TEXT_LEN))
            }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (out.size < MAX_TEXTS && !out.contains(it)) out.add(it.take(MAX_TEXT_LEN))
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                queue.addLast(child)
            }
        }
        return out
    }

    override fun onInterrupt() {
        // 无长任务，无需处理
    }

    override fun onDestroy() {
        runCatching { scope.cancel() }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "LedgerA11yService"
        const val THROTTLE_MS = 300L
        const val MAX_NODES = 500
        const val MAX_TEXTS = 80
        const val MAX_TEXT_LEN = 200
        val WATCHED_PACKAGES = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
    }
}

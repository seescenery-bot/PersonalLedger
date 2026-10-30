package com.mizhii.ledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.mizhii.ledger.MainActivity
import com.mizhii.ledger.R
import com.mizhii.ledger.data.db.DatabaseProvider
import com.mizhii.ledger.util.Dates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 「简账·本月账目」桌面小部件（M6）。
 *
 * 设计依据：HyperOS 小部件设计规范（dev.mi.com pId=1664）
 *  - 尺寸：2×2 / 4×2 / 4×4，**尺寸变大加信息密度，而不是放大字号**
 *  - 安全区：主要内容距边缘 ≥42px@1080p（≈14dp），这里用 16dp
 *  - 圆角：55px@1080p ≈ 18dp（`drawable/widget_bg.xml`）
 *  - 文字层级：标题/说明 40% 透明，数据用正文色；主要数字 Demibold
 *  - 深色模式：`values-night/colors.xml` 覆盖
 *  - 无内容场景：必须设计（`widget_empty`）
 *  - 禁止小部件内滑动 / 文字输入 —— RemoteViews 天然做不到
 *
 * ⚠️ 布局里**只能用**嵌套 LinearLayout + TextView 和显式属性。
 * 实测：`<View>`、`style="..."`、`layout_weight` 三种写法都会让 MIUI 的
 * `LauncherWidgetLayoutFactory`（它把我们的 LinearLayout 换成 `HomeMIUIWidgetLinearLayout`）
 * 在 apply 时失败，桌面上直接显示「载入窗口小部件时出现问题」。
 * 这是真机一次一次试出来的，别"优化"回去。
 */
class LedgerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // onUpdate 在广播超时窗口里跑，读数据库必须异步
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                val snapshot = loadSnapshot(appContext)
                val ids = if (appWidgetIds.isNotEmpty()) appWidgetIds else currentIds(appContext)
                for (id in ids) {
                    runCatching {
                        val layout = layoutFor(appWidgetManager, id)
                        appWidgetManager.updateAppWidget(id, buildViews(appContext, snapshot, layout))
                    }.onFailure { Log.w(TAG, "update widget $id failed: ${it.message}") }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "widget refresh failed: ${t.message}")
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    /** 拖动改变大小时立刻换布局 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    // ── 数据 ───────────────────────────────────────────────────────

    private suspend fun loadSnapshot(context: Context): WidgetSnapshot {
        val dao = DatabaseProvider.get(context).txnDao()
        val monthKey = Dates.currentMonthKey()
        val summary = dao.monthSummaryOnce(monthKey)
        val count = dao.monthTxnCount(monthKey)
        val recent = dao.recentConfirmed(WidgetText.MAX_RECENT_TALL)
        return WidgetSnapshot(
            monthKey = monthKey,
            expenseCents = summary.expenseCents,
            incomeCents = summary.incomeCents,
            txnCount = count,
            recent = recent.map {
                WidgetRecent(
                    id = it.id,
                    counterparty = it.counterparty,
                    amountCents = it.amountCents,
                    direction = it.direction,
                    dayKey = it.dayKey,
                )
            },
        )
    }

    // ── 渲染 ───────────────────────────────────────────────────────

    /**
     * Android 的尺寸换算是 70dp × 格数 − 30dp：
     * 2×2 → 110dp，4×2 → 250×110dp，4×4 → 250×250dp。
     * 部分桌面首次添加时 OPTION_APPWIDGET_MIN_WIDTH 是 0，兜底按 2×2。
     */
    private fun layoutFor(manager: AppWidgetManager, id: Int): Int {
        val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
        val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
        return when {
            width >= 200 && height >= 200 -> R.layout.widget_ledger_4x4
            width >= 200 -> R.layout.widget_ledger_4x2
            else -> R.layout.widget_ledger_2x2
        }
    }

    private fun buildViews(context: Context, snapshot: WidgetSnapshot, layoutId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))

        if (!snapshot.hasData) {
            views.setViewVisibility(R.id.widget_body, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty_title, WidgetText.emptyTitle())
            views.setTextViewText(R.id.widget_empty_hint, WidgetText.emptyHint())
            views.setContentDescription(
                R.id.widget_root,
                WidgetText.emptyTitle() + "。" + WidgetText.emptyHint()
            )
            return views
        }

        views.setTextViewText(R.id.widget_month, WidgetText.monthLabel(snapshot.monthKey) + "支出")
        views.setTextViewText(R.id.widget_expense, WidgetText.expenseText(snapshot))
        views.setTextViewText(R.id.widget_income, "收入 " + WidgetText.incomeText(snapshot))
        views.setTextViewText(R.id.widget_balance, "结余 " + WidgetText.balanceText(snapshot))
        views.setTextViewText(R.id.widget_recent_title, "最近 · 本月 " + snapshot.txnCount + " 笔")

        bindRecent(views, snapshot)

        // 无障碍：小部件是"只展示 + 整块跳转"，按规范整块播报一次即可
        views.setContentDescription(R.id.widget_root, accessibilitySummary(snapshot))
        return views
    }

    private fun bindRecent(views: RemoteViews, snapshot: WidgetSnapshot) {
        for (i in ROW_TITLES.indices) {
            val item = snapshot.recent.getOrNull(i)
            if (item == null) {
                // 布局里是固定行，不能动态删，没数据就把文字清空
                views.setTextViewText(ROW_TITLES[i], "")
                views.setTextViewText(ROW_SUBS[i], "")
                continue
            }
            views.setTextViewText(ROW_TITLES[i], WidgetText.recentTitle(item))
            views.setTextViewText(
                ROW_SUBS[i],
                WidgetText.signedAmount(item) + "  ·  " + WidgetText.recentSubtitle(item)
            )
        }
    }

    /** 读屏用的整块描述 */
    private fun accessibilitySummary(snapshot: WidgetSnapshot): String {
        val head = WidgetText.monthLabel(snapshot.monthKey) + "支出 " + WidgetText.expenseText(snapshot) +
            "，收入 " + WidgetText.incomeText(snapshot) +
            "，结余 " + WidgetText.balanceText(snapshot)
        val last = snapshot.recent.firstOrNull() ?: return head
        return head + "。最近一笔：" + WidgetText.recentTitle(last) + " " + WidgetText.signedAmount(last)
    }

    /** 点整块 → 直接落到流水列表（规范：不要只跳应用首页） */
    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "LedgerWidget"
        private const val REQUEST_CODE = 0x5701

        private val ROW_TITLES = intArrayOf(
            R.id.widget_r1_title, R.id.widget_r2_title, R.id.widget_r3_title,
            R.id.widget_r4_title, R.id.widget_r5_title
        )
        private val ROW_SUBS = intArrayOf(
            R.id.widget_r1_sub, R.id.widget_r2_sub, R.id.widget_r3_sub,
            R.id.widget_r4_sub, R.id.widget_r5_sub
        )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun currentIds(context: Context): IntArray = runCatching {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, LedgerWidgetProvider::class.java))
        }.getOrDefault(IntArray(0))

        /**
         * 数据变了就刷新小部件。走系统广播而不是直接 update：
         * 这样 onUpdate 里的 goAsync() 才有意义，进程也不必常驻。
         */
        fun refresh(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val ids = currentIds(appContext)
                if (ids.isEmpty()) return
                val intent = Intent(appContext, LedgerWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                appContext.sendBroadcast(intent)
            }.onFailure { Log.w(TAG, "refresh broadcast failed: ${it.message}") }
        }
    }
}

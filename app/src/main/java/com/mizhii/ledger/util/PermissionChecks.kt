package com.mizhii.ledger.util

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.mizhii.ledger.service.LedgerAccessibilityService

/**
 * 权限与后台状态的检查（需求文档 §7.7）。
 *
 * 存在的理由很直接：2026-10-30 的事故里，用户以为"权限开了"，实际上
 * `enabled_notification_listeners` 里只有采集工具、没有本应用 ——
 * 通道 A 静默空转了很久，界面上却看不出任何异常。
 *
 * 权限是**按 app 授予**的，给别的 app 开了不等于给自己开了，所以必须自己查。
 *
 * 后台两项（backgroundRestricted / batteryUnrestricted）来自小米官方文档
 * <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1628>：
 * 文档明确要求应用自己调 `isBackgroundRestricted()` 判断是否被限制，
 * 并给出用户侧的解除路径「设置-省电与电池-右上角-应用智能省电-无限制」。
 */
data class PermissionHealth(
    val notifListenerEnabled: Boolean,
    val a11yEnabled: Boolean,
    /** 系统通知权限（POST_NOTIFICATIONS）。缺了只是收不到记账提醒，不影响记账 */
    val notificationsEnabled: Boolean,
    val lastNotifConnectedAt: Long,
    val lastA11yConnectedAt: Long,
    /** `ActivityManager.isBackgroundRestricted()`：true = 系统/用户限制了后台运行 */
    val backgroundRestricted: Boolean = false,
    /** `PowerManager.isIgnoringBatteryOptimizations()`：true = 省电策略为「无限制」 */
    val batteryUnrestricted: Boolean = false,
) {
    /** 自动记账本身是否可用（只看两个通道） */
    val allGood: Boolean get() = notifListenerEnabled && a11yEnabled

    val problems: List<String> = buildList {
        if (!notifListenerEnabled) {
            add("通知使用权未开启 —— 微信/支付宝的实时通知完全收不到（通道 A 静默）")
        }
        if (!a11yEnabled) {
            add("无障碍服务未开启 —— 支付成功页读不到，商户名只能靠通知碰运气（通道 B 静默）")
        }
        if (!notificationsEnabled) {
            add("系统通知权限未授予 —— 记账提醒发不出来（记账本身不受影响，可在设置里开）")
        }
        if (backgroundRestricted) {
            add("系统限制了本应用的后台运行 —— 自动记账会时断时续")
        }
    }
}

object PermissionChecks {

    /**
     * 纯函数：从 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 的原始值判断某服务是否启用。
     *
     * 原始值形如 `pkg1/pkg1.Svc:com.mizhii.ledger/.service.LedgerAccessibilityService`，
     * 类名有「全限定」和「相对（以 . 开头）」两种写法，都要认。
     */
    fun isA11yServiceEnabled(raw: String?, packageName: String, className: String): Boolean {
        if (raw.isNullOrBlank()) return false
        for (entry in raw.split(':')) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            val slash = trimmed.indexOf('/')
            if (slash <= 0) continue
            val pkg = trimmed.substring(0, slash)
            if (pkg != packageName) continue
            val cls = trimmed.substring(slash + 1)
            val expanded = if (cls.startsWith(".")) packageName + cls else cls
            if (expanded == className) return true
        }
        return false
    }

    fun check(context: Context): PermissionHealth {
        val notif = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }.getOrDefault(false)

        val a11yRaw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull()
        val a11y = isA11yServiceEnabled(
            raw = a11yRaw,
            packageName = context.packageName,
            className = LedgerAccessibilityService::class.java.name
        )
        val notificationsEnabled = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)

        // 小米官方文档点名的唯一可编程信号（pId=1628）
        val backgroundRestricted = runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .isBackgroundRestricted()
        }.getOrDefault(false)

        // 进了 Doze 白名单 ≈ 澎湃OS 的「省电策略：无限制」
        val batteryUnrestricted = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

        return PermissionHealth(
            notifListenerEnabled = notif,
            a11yEnabled = a11y,
            notificationsEnabled = notificationsEnabled,
            lastNotifConnectedAt = 0,
            lastA11yConnectedAt = 0,
            backgroundRestricted = backgroundRestricted,
            batteryUnrestricted = batteryUnrestricted,
        )
    }

    /** 带上"最近一次连接时间"的完整检查 */
    fun check(context: Context, lastNotifConnectedAt: Long, lastA11yConnectedAt: Long): PermissionHealth =
        check(context).copy(
            lastNotifConnectedAt = lastNotifConnectedAt,
            lastA11yConnectedAt = lastA11yConnectedAt
        )
}

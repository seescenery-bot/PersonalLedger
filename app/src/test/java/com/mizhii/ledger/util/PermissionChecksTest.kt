package com.mizhii.ledger.util

import com.mizhii.ledger.service.LedgerAccessibilityService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限状态判定的纯函数测试。
 *
 * 这类逻辑一旦判错，界面上就会显示"已开启"而实际没开 —— 正是 2026-10-30 事故的形态，
 * 所以值不值得测？值得。
 */
class PermissionChecksTest {

    private val pkg = "com.mizhii.ledger"
    private val cls = "com.mizhii.ledger.service.LedgerAccessibilityService"

    @Test
    fun `全限定类名能识别`() {
        val raw = "com.other.app/com.other.app.Svc:$pkg/$cls"
        assertTrue(PermissionChecks.isA11yServiceEnabled(raw, pkg, cls))
    }

    @Test
    fun `相对类名（以点开头）能识别`() {
        val raw = "com.other.app/com.other.app.Svc:$pkg/.service.LedgerAccessibilityService"
        assertTrue(PermissionChecks.isA11yServiceEnabled(raw, pkg, cls))
    }

    @Test
    fun `只有别的应用时不算开启`() {
        val raw = "com.xiaomi.barrage/com.xiaomi.barrage.NotificationMonitorService"
        assertFalse(PermissionChecks.isA11yServiceEnabled(raw, pkg, cls))
    }

    @Test
    fun `空值与空串不算开启`() {
        assertFalse(PermissionChecks.isA11yServiceEnabled(null, pkg, cls))
        assertFalse(PermissionChecks.isA11yServiceEnabled("", pkg, cls))
        assertFalse(PermissionChecks.isA11yServiceEnabled("  ", pkg, cls))
    }

    @Test
    fun `包名相同但类名不同不算开启`() {
        val raw = "$pkg/$pkg.service.OtherService"
        assertFalse(PermissionChecks.isA11yServiceEnabled(raw, pkg, cls))
    }

    @Test
    fun `真实真机格式（三服务串联）能识别`() {
        // 2026-10-30 手机上的真实值
        val raw = "com.xiaomi.aicr/com.google.android.accessibility.selecttospeak.SelectToSpeakService:" +
            "$pkg/$cls:" +
            "com.mipay.wallet/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        assertTrue(PermissionChecks.isA11yServiceEnabled(raw, pkg, cls))
    }

    @Test
    fun `服务类名常量与真实类一致`() {
        // 防止重构改包名后 UI 一直显示"未开启"
        assertTrue(LedgerAccessibilityService::class.java.name.endsWith(".service.LedgerAccessibilityService"))
    }
}

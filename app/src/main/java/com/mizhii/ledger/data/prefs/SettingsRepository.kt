package com.mizhii.ledger.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ledger_settings")

/** 设置项（文档 §8.8） */
data class LedgerSettings(
    val notifChannelEnabled: Boolean = true,
    val a11yChannelEnabled: Boolean = true,
    val autoConfirmThreshold: Int = 70,
    val dedupeWindowSeconds: Int = 90,
    val minAmountCents: Long = 0,
    val lastNotifConnectedAt: Long = 0,
    val lastA11yConnectedAt: Long = 0,
    /** 新建一笔账后是否推送通知 */
    val notifyOnCapture: Boolean = true,
    /** 调试用：接收任意包名的通知。仅 debug 构建可见，用于在没有真实支付时验证整条管线 */
    val debugAcceptAllPackages: Boolean = false
)

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<LedgerSettings> = store.data.map { it.toSettings() }

    suspend fun current(): LedgerSettings = settings.first()

    suspend fun setNotifChannelEnabled(value: Boolean) = putBoolean(KEY_NOTIF_ENABLED, value)

    suspend fun setA11yChannelEnabled(value: Boolean) = putBoolean(KEY_A11Y_ENABLED, value)

    suspend fun setAutoConfirmThreshold(value: Int) =
        store.edit { it[KEY_THRESHOLD] = value.coerceIn(40, 95) }

    suspend fun setDedupeWindowSeconds(value: Int) =
        store.edit { it[KEY_DEDUPE_WINDOW] = value.coerceIn(10, 600) }

    suspend fun setMinAmountCents(value: Long) = store.edit { it[KEY_MIN_AMOUNT] = value }

    suspend fun setDebugAcceptAllPackages(value: Boolean) = putBoolean(KEY_DEBUG_ANY_PKG, value)

    suspend fun setNotifyOnCapture(value: Boolean) = putBoolean(KEY_NOTIFY_ON_CAPTURE, value)

    suspend fun markNotifConnected() = store.edit { it[KEY_LAST_NOTIF] = System.currentTimeMillis() }

    suspend fun markA11yConnected() = store.edit { it[KEY_LAST_A11Y] = System.currentTimeMillis() }

    private suspend fun putBoolean(key: Preferences.Key<Boolean>, value: Boolean) =
        store.edit { it[key] = value }

    private fun Preferences.toSettings() = LedgerSettings(
        notifChannelEnabled = this[KEY_NOTIF_ENABLED] ?: true,
        a11yChannelEnabled = this[KEY_A11Y_ENABLED] ?: true,
        autoConfirmThreshold = this[KEY_THRESHOLD] ?: 70,
        dedupeWindowSeconds = this[KEY_DEDUPE_WINDOW] ?: 90,
        minAmountCents = this[KEY_MIN_AMOUNT] ?: 0L,
        lastNotifConnectedAt = this[KEY_LAST_NOTIF] ?: 0L,
        lastA11yConnectedAt = this[KEY_LAST_A11Y] ?: 0L,
        notifyOnCapture = this[KEY_NOTIFY_ON_CAPTURE] ?: true,
        debugAcceptAllPackages = this[KEY_DEBUG_ANY_PKG] ?: false
    )

    private companion object {
        val KEY_NOTIF_ENABLED = booleanPreferencesKey("notif_channel_enabled")
        val KEY_A11Y_ENABLED = booleanPreferencesKey("a11y_channel_enabled")
        val KEY_THRESHOLD = intPreferencesKey("auto_confirm_threshold")
        val KEY_DEDUPE_WINDOW = intPreferencesKey("dedupe_window_seconds")
        val KEY_MIN_AMOUNT = longPreferencesKey("min_amount_cents")
        val KEY_LAST_NOTIF = longPreferencesKey("last_notif_connected_at")
        val KEY_LAST_A11Y = longPreferencesKey("last_a11y_connected_at")
        val KEY_DEBUG_ANY_PKG = booleanPreferencesKey("debug_accept_all_packages")
        val KEY_NOTIFY_ON_CAPTURE = booleanPreferencesKey("notify_on_capture")
    }
}

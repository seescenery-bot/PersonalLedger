package com.mizhii.ledger.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mizhii.ledger.LedgerApp
import com.mizhii.ledger.data.prefs.LedgerSettings
import com.mizhii.ledger.data.prefs.SettingsRepository
import com.mizhii.ledger.data.repo.TxnRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    txnRepository: TxnRepository
) : ViewModel() {

    val settings: StateFlow<LedgerSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerSettings())

    val pendingCount: StateFlow<Int> = txnRepository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setNotifEnabled(value: Boolean) =
        viewModelScope.launch { settingsRepository.setNotifChannelEnabled(value) }

    fun setA11yEnabled(value: Boolean) =
        viewModelScope.launch { settingsRepository.setA11yChannelEnabled(value) }

    fun setThreshold(value: Int) =
        viewModelScope.launch { settingsRepository.setAutoConfirmThreshold(value) }

    fun setNotifyOnCapture(value: Boolean) =
        viewModelScope.launch { settingsRepository.setNotifyOnCapture(value) }

    fun setDedupeWindow(value: Int) =
        viewModelScope.launch { settingsRepository.setDedupeWindowSeconds(value) }

    fun setDebugAcceptAll(value: Boolean) =
        viewModelScope.launch { settingsRepository.setDebugAcceptAllPackages(value) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as LedgerApp
                SettingsViewModel(
                    settingsRepository = app.container.settingsRepository,
                    txnRepository = app.container.txnRepository
                )
            }
        }
    }
}

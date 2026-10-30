package com.mizhii.ledger.ui.diag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mizhii.ledger.LedgerApp
import com.mizhii.ledger.data.db.entity.RawCaptureEntity
import com.mizhii.ledger.data.repo.RawCaptureRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DiagnosticsViewModel(
    private val rawCaptureRepository: RawCaptureRepository
) : ViewModel() {

    val entries: StateFlow<List<RawCaptureEntity>> = rawCaptureRepository.observeRecent(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _cleared = MutableStateFlow<Int?>(null)
    val cleared: StateFlow<Int?> = _cleared.asStateFlow()

    fun clearOlderThanSevenDays() {
        viewModelScope.launch { _cleared.value = rawCaptureRepository.clearOlderThan(7) }
    }

    fun consumeCleared() {
        _cleared.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as LedgerApp
                DiagnosticsViewModel(app.container.rawCaptureRepository)
            }
        }
    }
}

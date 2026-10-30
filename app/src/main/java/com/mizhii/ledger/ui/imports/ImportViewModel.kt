package com.mizhii.ledger.ui.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mizhii.ledger.LedgerApp
import com.mizhii.ledger.domain.import.BillImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账单导入（M4）。UI 只有两个阶段：**预览 → 确认导入**。
 * 解析放在 [Dispatchers.Default]，因为 xlsx 解压 + DOM 解析是纯 CPU 活。
 */
class ImportViewModel(private val importer: BillImporter) : ViewModel() {

    data class UiState(
        val busy: Boolean = false,
        val fileName: String? = null,
        val preview: BillImporter.Preview? = null,
        val result: BillImporter.Result? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun selectFile(fileName: String, bytes: ByteArray) {
        viewModelScope.launch {
            _state.value = UiState(busy = true, fileName = fileName)
            runCatching {
                withContext(Dispatchers.Default) { importer.preview(fileName, bytes) }
            }.onSuccess { preview ->
                _state.value = UiState(fileName = fileName, preview = preview)
            }.onFailure { e ->
                _state.value = UiState(
                    fileName = fileName,
                    error = e.message ?: "账单解析失败"
                )
            }
        }
    }

    fun confirmImport() {
        val preview = _state.value.preview ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching { importer.import(preview) }
                .onSuccess { result ->
                    _state.value = UiState(fileName = preview.fileName, result = result)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(busy = false, error = e.message ?: "导入失败")
                }
        }
    }

    fun reset() {
        _state.value = UiState()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as LedgerApp
                ImportViewModel(app.container.billImporter)
            }
        }
    }
}

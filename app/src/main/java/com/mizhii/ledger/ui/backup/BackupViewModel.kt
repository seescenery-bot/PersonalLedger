package com.mizhii.ledger.ui.backup

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mizhii.ledger.LedgerApp
import com.mizhii.ledger.crypto.CorruptBackupException
import com.mizhii.ledger.crypto.WrongPasswordException
import com.mizhii.ledger.domain.backup.BackupContent
import com.mizhii.ledger.domain.backup.BackupService
import com.mizhii.ledger.domain.backup.BackupSummary
import com.mizhii.ledger.domain.backup.RestoreMode
import com.mizhii.ledger.domain.backup.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份与恢复（M5 §7.6）。
 *
 * 状态机刻意做成"先解密、看清楚、再决定"：
 * 恢复是破坏性操作，用户点下之前必须知道这份备份是什么时候的、有多少笔。
 */
class BackupViewModel(
    private val service: BackupService,
    private val contentResolver: ContentResolver,
) : ViewModel() {

    enum class Step {
        IDLE,
        /** 备份文件已读入，等用户给口令 */
        NEED_PASSWORD,
        /** 解密成功，等用户决定合并还是替换 */
        CONFIRM_RESTORE,
        DONE,
    }

    data class UiState(
        val busy: Boolean = false,
        val step: Step = Step.IDLE,
        val fileName: String? = null,
        val blobSize: Int = 0,
        val pendingRestore: BackupSummary? = null,
        val exportSummary: BackupSummary? = null,
        val restoreResult: RestoreResult? = null,
        val error: String? = null,
        val notice: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 解密后的内容，仅在 CONFIRM_RESTORE 阶段短暂持有 */
    private var pendingContent: BackupContent? = null

    // ── 导出 ───────────────────────────────────────────────────────

    fun export(password: CharArray, target: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    // 只读一次库：摘要与实际写进文件的必须是同一份内容
                    val content = service.buildContent()
                    val blob = service.encryptContent(content, password)
                    contentResolver.openOutputStream(target)?.use { it.write(blob) }
                        ?: error("无法写入目标文件")
                    service.summarize(content) to blob.size
                }
            }.onSuccess { (summary, size) ->
                _state.value = UiState(
                    exportSummary = summary,
                    notice = "备份已保存（${formatSize(size)}）",
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(busy = false, error = humanError(e))
            }
            password.fill('\u0000')
        }
    }

    // ── 恢复 ───────────────────────────────────────────────────────

    fun onFilePicked(fileName: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            _state.value = UiState(error = "这个文件是空的")
            return
        }
        _state.value = UiState(
            step = Step.NEED_PASSWORD,
            fileName = fileName,
            blobSize = bytes.size,
        )
        pendingBlob = bytes
    }

    private var pendingBlob: ByteArray? = null

    fun unlock(password: CharArray) {
        val blob = pendingBlob ?: run {
            _state.value = UiState(error = "没有待恢复的文件，请重新选择")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching {
                // PBKDF2 200k 次迭代是 CPU 活，放 Default
                withContext(Dispatchers.Default) { service.openBackup(password, blob) }
            }.onSuccess { content ->
                pendingContent = content
                _state.value = _state.value.copy(
                    busy = false,
                    step = Step.CONFIRM_RESTORE,
                    pendingRestore = service.summarize(content),
                    error = null,
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(busy = false, error = humanError(e))
            }
            password.fill('\u0000')
        }
    }

    fun commitRestore(mode: RestoreMode) {
        val content = pendingContent ?: run {
            _state.value = _state.value.copy(error = "待恢复的数据已失效，请重新选择文件")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) { service.applyRestore(content, mode) }
            }.onSuccess { result ->
                pendingContent = null
                pendingBlob = null
                _state.value = UiState(step = Step.DONE, restoreResult = result)
            }.onFailure { e ->
                _state.value = _state.value.copy(busy = false, error = humanError(e))
            }
        }
    }

    fun reset() {
        pendingContent = null
        pendingBlob = null
        _state.value = UiState()
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /** 把技术异常翻译成用户能看懂的一句话 */
    private fun humanError(e: Throwable): String = when (e) {
        is WrongPasswordException ->
            "口令不对，或者文件被改过。备份口令没有找回途径，请换一个密码再试。"
        is CorruptBackupException -> e.message ?: "备份文件无法识别"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private fun formatSize(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as LedgerApp
                BackupViewModel(
                    service = app.container.backupService,
                    contentResolver = app.contentResolver,
                )
            }
        }
    }
}

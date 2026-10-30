package com.mizhii.ledger.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mizhii.ledger.LedgerApp
import com.mizhii.ledger.data.db.dao.MonthSummary
import com.mizhii.ledger.data.db.entity.CategoryEntity
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.TxnEntity
import com.mizhii.ledger.data.repo.CategoryRepository
import com.mizhii.ledger.data.repo.TxnRepository
import com.mizhii.ledger.ui.edit.TxnEditRequest
import com.mizhii.ledger.util.Dates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 按天分组后的展示模型 */
data class DayGroup(
    val dayKey: String,
    val expenseCents: Long,
    val incomeCents: Long,
    val items: List<TxnEntity>
)

data class HomeUiState(
    val monthKey: String = Dates.currentMonthKey(),
    val summary: MonthSummary = MonthSummary(0L, 0L),
    val groups: List<DayGroup> = emptyList(),
    val pendingCount: Int = 0,
    val categories: List<CategoryEntity> = emptyList(),
    val loaded: Boolean = false
) {
    fun categoryName(id: String?): String? = categories.firstOrNull { it.id == id }?.name
    fun category(id: String?): CategoryEntity? = categories.firstOrNull { it.id == id }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val txnRepository: TxnRepository,
    categoryRepository: CategoryRepository
) : ViewModel() {

    private val _monthKey = MutableStateFlow(Dates.currentMonthKey())
    val monthKey: StateFlow<String> = _monthKey.asStateFlow()

    /** 刚被删除的流水，用于 Snackbar 撤销 */
    private val _deleted = MutableStateFlow<TxnEntity?>(null)
    val deleted: StateFlow<TxnEntity?> = _deleted.asStateFlow()

    val uiState: StateFlow<HomeUiState> = combine(
        _monthKey,
        _monthKey.flatMapLatest { txnRepository.observeMonth(it) },
        _monthKey.flatMapLatest { txnRepository.observeMonthSummary(it) },
        txnRepository.observePendingCount(),
        categoryRepository.observeAll()
    ) { monthKey, txns, summary, pending, categories ->
        HomeUiState(
            monthKey = monthKey,
            summary = summary,
            groups = groupByDay(txns),
            pendingCount = pending,
            categories = categories,
            loaded = true
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )

    fun changeMonth(delta: Int) {
        _monthKey.value = Dates.previousMonth(_monthKey.value, delta)
    }

    fun setMonth(monthKey: String) {
        _monthKey.value = monthKey
    }

    fun resetToCurrentMonth() {
        _monthKey.value = Dates.currentMonthKey()
    }

    fun save(request: TxnEditRequest) {
        viewModelScope.launch {
            if (request.id == null) {
                txnRepository.insertManual(request.toInput())
            } else {
                txnRepository.updateFromEdit(request.id, request.toInput())
            }
            // 新增/编辑的流水可能在别的月份，切到该月才能看到
            val targetMonth = Dates.monthKey(request.occurredAt)
            if (targetMonth != _monthKey.value) _monthKey.value = targetMonth
        }
    }

    fun delete(txn: TxnEntity) {
        viewModelScope.launch {
            txnRepository.delete(txn.id)
            _deleted.value = txn
        }
    }

    fun undoDelete() {
        val target = _deleted.value ?: return
        viewModelScope.launch {
            txnRepository.restore(target)
            _deleted.value = null
        }
    }

    fun clearDeleted() {
        _deleted.value = null
    }

    private fun groupByDay(txns: List<TxnEntity>): List<DayGroup> =
        txns.groupBy { it.dayKey }.map { (dayKey, dayTxns) ->
            DayGroup(
                dayKey = dayKey,
                expenseCents = dayTxns.filter { it.direction == Direction.EXPENSE }.sumOf { it.amountCents },
                incomeCents = dayTxns.filter { it.direction == Direction.INCOME }.sumOf { it.amountCents },
                items = dayTxns
            )
        }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as LedgerApp
                HomeViewModel(
                    txnRepository = app.container.txnRepository,
                    categoryRepository = app.container.categoryRepository
                )
            }
        }
    }
}

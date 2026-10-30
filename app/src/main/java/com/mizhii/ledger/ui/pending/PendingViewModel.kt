package com.mizhii.ledger.ui.pending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mizhii.ledger.LedgerApp
import com.mizhii.ledger.data.db.entity.CategoryEntity
import com.mizhii.ledger.data.db.entity.TxnEntity
import com.mizhii.ledger.data.repo.CategoryRepository
import com.mizhii.ledger.data.repo.TxnRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 待确认队列（§7.3） */
class PendingViewModel(
    private val txnRepository: TxnRepository,
    categoryRepository: CategoryRepository
) : ViewModel() {

    val pending: StateFlow<List<TxnEntity>> = txnRepository.observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun confirm(id: Long) = viewModelScope.launch { txnRepository.confirm(id) }

    fun ignore(id: Long) = viewModelScope.launch { txnRepository.ignore(id) }

    fun delete(txn: TxnEntity) = viewModelScope.launch { txnRepository.delete(txn.id) }

    fun setCategory(id: Long, categoryId: String) =
        viewModelScope.launch { txnRepository.setCategory(id, categoryId) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as LedgerApp
                PendingViewModel(
                    txnRepository = app.container.txnRepository,
                    categoryRepository = app.container.categoryRepository
                )
            }
        }
    }
}

package com.mizhii.ledger.ui.rule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mizhii.ledger.LedgerApp
import com.mizhii.ledger.data.db.entity.CategoryEntity
import com.mizhii.ledger.data.db.entity.RuleEntity
import com.mizhii.ledger.data.repo.CategoryRepository
import com.mizhii.ledger.data.repo.RuleRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RuleViewModel(
    private val ruleRepository: RuleRepository,
    categoryRepository: CategoryRepository
) : ViewModel() {

    val rules: StateFlow<List<RuleEntity>> = ruleRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(rule: RuleEntity, enabled: Boolean) {
        viewModelScope.launch { ruleRepository.setEnabled(rule, enabled) }
    }

    fun delete(rule: RuleEntity) {
        viewModelScope.launch { ruleRepository.deleteCustom(rule) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as LedgerApp
                RuleViewModel(
                    ruleRepository = app.container.ruleRepository,
                    categoryRepository = app.container.categoryRepository
                )
            }
        }
    }
}

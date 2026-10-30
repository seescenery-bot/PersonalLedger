package com.mizhii.ledger.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mizhii.ledger.LedgerApp
import com.mizhii.ledger.data.db.dao.ChannelTotal
import com.mizhii.ledger.data.db.dao.DailyTotal
import com.mizhii.ledger.data.db.dao.MerchantTotal
import com.mizhii.ledger.data.repo.CategoryRepository
import com.mizhii.ledger.data.repo.TxnRepository
import com.mizhii.ledger.util.Dates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

/** 环形图的一个扇区 */
data class CategorySlice(
    val categoryId: String?,
    val name: String,
    val colorHex: String,
    val totalCents: Long,
    val count: Int,
    val percent: Float
)

data class StatsUiState(
    val monthKey: String = Dates.currentMonthKey(),
    val expenseCents: Long = 0,
    val incomeCents: Long = 0,
    val prevExpenseCents: Long = 0,
    val slices: List<CategorySlice> = emptyList(),
    val channels: List<ChannelTotal> = emptyList(),
    val daily: List<DailyTotal> = emptyList(),
    val merchants: List<MerchantTotal> = emptyList(),
    val daysInMonth: Int = YearMonth.now().lengthOfMonth(),
    val loaded: Boolean = false
) {
    val balanceCents: Long get() = incomeCents - expenseCents

    /** 环比。上月为 0 时无意义，返回 null */
    val monthOverMonthPercent: Float?
        get() = if (prevExpenseCents <= 0L) {
            null
        } else {
            (expenseCents - prevExpenseCents).toFloat() / prevExpenseCents * 100f
        }

    val hasData: Boolean get() = expenseCents > 0 || incomeCents > 0
}

/**
 * 月度统计（需求文档 §7.5）。
 * 统计口径：只统计 `status = CONFIRMED`，月份归属以 occurredAt 的本地时区为准。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    txnRepository: TxnRepository,
    categoryRepository: CategoryRepository
) : ViewModel() {

    private val _monthKey = MutableStateFlow(Dates.currentMonthKey())
    val monthKey: StateFlow<String> = _monthKey.asStateFlow()

    val uiState: StateFlow<StatsUiState> = _monthKey.flatMapLatest { monthKey ->
        val prevMonth = Dates.previousMonth(monthKey, -1)

        val core = combine(
            txnRepository.observeMonthSummary(monthKey),
            txnRepository.observeMonthSummary(prevMonth),
            txnRepository.observeCategoryBreakdown(monthKey)
        ) { summary, prevSummary, categories -> Triple(summary, prevSummary, categories) }

        val extra = combine(
            txnRepository.observeChannelBreakdown(monthKey),
            txnRepository.observeDailyExpense(monthKey),
            txnRepository.observeTopMerchants(monthKey)
        ) { channels, daily, merchants -> Triple(channels, daily, merchants) }

        combine(core, extra, categoryRepository.observeAll()) { c, e, categories ->
            val names = categories.associate { it.id to it.name }
            val colors = categories.associate { it.id to it.colorHex }
            val total = c.first.expenseCents

            // 环形图最多画 8 个扇区，其余合并为「其他」
            val raw = c.third
            val top = raw.take(8)
            val restTotal = raw.drop(8).sumOf { it.totalCents }
            val slices = buildList {
                top.forEach { item ->
                    add(
                        CategorySlice(
                            categoryId = item.categoryId,
                            name = item.categoryId?.let { names[it] } ?: "未分类",
                            colorHex = item.categoryId?.let { colors[it] } ?: "#909499",
                            totalCents = item.totalCents,
                            count = item.count,
                            percent = if (total > 0) item.totalCents.toFloat() / total else 0f
                        )
                    )
                }
                if (restTotal > 0) {
                    add(
                        CategorySlice(
                            categoryId = null,
                            name = "其他",
                            colorHex = "#909499",
                            totalCents = restTotal,
                            count = raw.drop(8).sumOf { it.count },
                            percent = if (total > 0) restTotal.toFloat() / total else 0f
                        )
                    )
                }
            }

            StatsUiState(
                monthKey = monthKey,
                expenseCents = c.first.expenseCents,
                incomeCents = c.first.incomeCents,
                prevExpenseCents = c.second.expenseCents,
                slices = slices,
                channels = e.first,
                daily = e.second,
                merchants = e.third,
                daysInMonth = YearMonth.parse(monthKey).lengthOfMonth(),
                loaded = true
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatsUiState()
    )

    fun changeMonth(delta: Int) {
        _monthKey.value = Dates.previousMonth(_monthKey.value, delta)
    }

    fun setMonth(monthKey: String) {
        _monthKey.value = monthKey
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as LedgerApp
                StatsViewModel(
                    txnRepository = app.container.txnRepository,
                    categoryRepository = app.container.categoryRepository
                )
            }
        }
    }
}

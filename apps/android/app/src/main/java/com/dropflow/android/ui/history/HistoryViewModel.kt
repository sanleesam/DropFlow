package com.dropflow.android.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.dropflow.android.core.model.TransferItem
import com.dropflow.android.storage.TransferHistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

class HistoryViewModel(
    private val history: TransferHistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            history.entries.collect { items ->
                _uiState.update { current -> current.copy(
                    allHistoryItems = items,
                    filteredItems = applyFilter(items, current.selectedFilterTab, current.searchQuery)
                ) }
            }
        }
    }

    fun setFilterTab(tab: HistoryFilterTab) {
        _uiState.update { current -> current.copy(
            selectedFilterTab = tab,
            filteredItems = applyFilter(current.allHistoryItems, tab, current.searchQuery)
        ) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { current -> current.copy(
            searchQuery = query,
            filteredItems = applyFilter(current.allHistoryItems, current.selectedFilterTab, query)
        ) }
    }

    private fun applyFilter(items: List<TransferItem>, tab: HistoryFilterTab, query: String): List<TransferItem> {
        return items.filter { item ->
            val matchesTab = when (tab) {
                HistoryFilterTab.SENT -> item.direction == com.dropflow.android.core.model.TransferDirection.SEND
                HistoryFilterTab.RECEIVED -> item.direction == com.dropflow.android.core.model.TransferDirection.RECEIVE
                HistoryFilterTab.ALL -> true
            }
            val matchesSearch = query.isBlank() || item.fileName.contains(query, ignoreCase = true) ||
                item.deviceName.contains(query, ignoreCase = true) ||
                item.files.any { it.relativePath.contains(query, ignoreCase = true) }
            matchesTab && matchesSearch
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            try {
                history.clear()
                _uiState.update { it.copy(errorMessage = null) }
            } catch (_: IOException) {
                _uiState.update { it.copy(errorMessage = "Could not clear transfer history. Try again.") }
            }
        }
    }

    companion object {
        /** Factory so the instance survives configuration changes via ViewModelProvider. */
        fun factory(history: TransferHistoryRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HistoryViewModel(history)
            }
        }
    }
}

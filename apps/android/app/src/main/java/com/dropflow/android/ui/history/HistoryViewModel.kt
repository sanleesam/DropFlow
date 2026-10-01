package com.dropflow.android.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dropflow.android.core.model.TransferItem
import com.dropflow.android.core.network.transfer.TransferEngine
import com.dropflow.android.core.network.transfer.TransferEngineListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HistoryViewModel(
    private val transferEngine: TransferEngine
) : ViewModel(), TransferEngineListener {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        transferEngine.addListener(this)
    }

    fun setFilterTab(tab: HistoryFilterTab) {
        val current = _uiState.value
        _uiState.value = current.copy(
            selectedFilterTab = tab,
            filteredItems = applyFilter(current.allHistoryItems, tab, current.searchQuery)
        )
    }

    fun setSearchQuery(query: String) {
        val current = _uiState.value
        _uiState.value = current.copy(
            searchQuery = query,
            filteredItems = applyFilter(current.allHistoryItems, current.selectedFilterTab, query)
        )
    }

    private fun applyFilter(items: List<TransferItem>, tab: HistoryFilterTab, query: String): List<TransferItem> {
        return items.filter { item ->
            val matchesTab = when (tab) {
                HistoryFilterTab.SENT -> item.direction == com.dropflow.android.core.model.TransferDirection.SEND
                HistoryFilterTab.RECEIVED -> item.direction == com.dropflow.android.core.model.TransferDirection.RECEIVE
                HistoryFilterTab.ALL -> true
            }
            val matchesSearch = query.isBlank() || item.fileName.contains(query, ignoreCase = true) || item.deviceName.contains(query, ignoreCase = true)
            matchesTab && matchesSearch
        }
    }

    override fun onTransferCompleted(transfer: TransferItem) {
        addHistoryItem(transfer)
    }

    override fun onTransferFailed(transfer: TransferItem) {
        addHistoryItem(transfer)
    }

    override fun onCleared() {
        transferEngine.removeListener(this)
        super.onCleared()
    }

    private fun addHistoryItem(transfer: TransferItem) {
        val current = _uiState.value
        val currentList = listOf(transfer) + current.allHistoryItems.filterNot { it.id == transfer.id }
        _uiState.value = current.copy(
            allHistoryItems = currentList,
            filteredItems = applyFilter(currentList, current.selectedFilterTab, current.searchQuery)
        )
    }

    companion object {
        /** Factory so the instance survives configuration changes via ViewModelProvider. */
        fun factory(transferEngine: TransferEngine): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HistoryViewModel(transferEngine)
            }
        }
    }
}

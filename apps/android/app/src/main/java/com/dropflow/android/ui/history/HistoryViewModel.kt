package com.dropflow.android.ui.history

import androidx.lifecycle.ViewModel
import com.dropflow.android.core.model.TransferItem
import com.dropflow.android.core.network.transfer.TransferEngineListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HistoryViewModel : ViewModel(), TransferEngineListener {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

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

    // --- TransferEngineListener Extension Hooks (Phase 3 Integration) ---

    override fun onIncomingTransferRequested(transfer: TransferItem) {
        // Will trigger transfer authorization dialog in Phase 3
    }

    override fun onTransferProgress(transferId: String, bytesTransferred: Long, totalBytes: Long) {
        // Will update active transfer progress
    }

    override fun onTransferCompleted(transfer: TransferItem) {
        val currentList = _uiState.value.allHistoryItems.toMutableList()
        currentList.add(0, transfer)
        val current = _uiState.value
        _uiState.value = current.copy(
            allHistoryItems = currentList,
            filteredItems = applyFilter(currentList, current.selectedFilterTab, current.searchQuery)
        )
    }

    override fun onTransferFailed(transferId: String, error: String) {
        // Handle failed transfer logging
    }
}

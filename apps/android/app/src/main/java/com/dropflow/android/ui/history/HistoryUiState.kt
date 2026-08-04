package com.dropflow.android.ui.history

import com.dropflow.android.core.model.TransferItem

enum class HistoryFilterTab(val key: String, val label: String) {
    ALL("all", "All"),
    SENT("send", "Sent"),
    RECEIVED("receive", "Received")
}

data class HistoryUiState(
    val selectedFilterTab: HistoryFilterTab = HistoryFilterTab.ALL,
    val searchQuery: String = "",
    val allHistoryItems: List<TransferItem> = emptyList(),
    val filteredItems: List<TransferItem> = emptyList()
)

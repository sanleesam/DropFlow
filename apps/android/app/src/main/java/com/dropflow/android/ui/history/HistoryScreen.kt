package com.dropflow.android.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.ui.components.*
import com.dropflow.android.ui.theme.*

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageIntro("YOUR ACTIVITY", "Transfers", "View your sent and received files during this app session.") }
        item {
            OutlinedTextField(state.searchQuery, viewModel::setSearchQuery,
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                placeholder = { Text("Find a file or device", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Default.Close, "Clear search")
                    }
                })
        }
        item {
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilterTab.entries.forEach { tab ->
                    FilterChip(state.selectedFilterTab == tab, { viewModel.setFilterTab(tab) },
                        label = { Text(tab.label) }, shape = RoundedCornerShape(9.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BlueAccentSoft,
                            selectedLabelColor = IcyBlue, labelColor = TextSecondary))
                }
            }
        }
        if (state.filteredItems.isEmpty()) item {
            EmptyState(Icons.Default.History, if (state.allHistoryItems.isEmpty()) "No recent transfers" else "No matching transfers",
                if (state.allHistoryItems.isEmpty()) "Sent and received files will appear here."
                else "Try a different search or switch the filter.")
        } else items(state.filteredItems, key = { it.id }) { TransferCard(it) }
    }
}

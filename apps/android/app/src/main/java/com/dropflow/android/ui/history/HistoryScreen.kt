package com.dropflow.android.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dropflow.android.ui.components.ButtonStyle
import com.dropflow.android.ui.components.CapsuleButton
import com.dropflow.android.ui.components.EmptyState
import com.dropflow.android.ui.components.ListCard
import com.dropflow.android.ui.components.Page
import com.dropflow.android.ui.components.cardItems
import com.dropflow.android.ui.components.SearchField
import com.dropflow.android.ui.components.SectionFooter
import com.dropflow.android.ui.components.Segmented
import com.dropflow.android.ui.components.TransferRow
import com.dropflow.android.ui.navigation.Screen
import com.dropflow.android.ui.theme.DropFlowType
import com.dropflow.android.ui.theme.Palette

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val colors = Palette
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        shape = RoundedCornerShape(28.dp),
        containerColor = colors.glassSolid,
        title = { Text("Clear history?", style = DropFlowType.headline) },
        text = { Text("Your files stay where they are.", style = DropFlowType.subhead, color = colors.label2) },
        confirmButton = {
            TextButton(onClick = { confirmClear = false; viewModel.clearHistory() }) {
                Text("Clear", color = colors.red, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = colors.accent) } }
    )
    Page(Screen.History.title, modifier) {
        state.errorMessage?.let { message ->
            item("error") { SectionFooter(message, Modifier.padding(bottom = 10.dp), color = colors.red) }
        }
        item("search") { SearchField(state.searchQuery, viewModel::setSearchQuery, "Find a file or device") }
        item("filter") {
            Spacer(Modifier.height(10.dp))
            Segmented(HistoryFilterTab.entries.map { it to it.label }, state.selectedFilterTab, viewModel::setFilterTab,
                Modifier.fillMaxWidth(), label = "Transfer filter")
            Spacer(Modifier.height(16.dp))
        }
        if (state.filteredItems.isEmpty()) item("empty") {
            ListCard {
                val none = state.allHistoryItems.isEmpty()
                EmptyState(Icons.Rounded.History, if (none) "No transfers yet" else "No matches",
                    message = if (none) "Sent and received files show up here." else "Try another search or filter.")
            }
        } else cardItems(state.filteredItems, key = { it.id }) { TransferRow(it) }
        item("footer") {
            SectionFooter(if (state.allHistoryItems.isEmpty()) "Your last 100 transfers stay on this device."
                else "${state.allHistoryItems.size} saved on this device · last 100 kept", Modifier.padding(top = 8.dp))
        }
        if (state.allHistoryItems.isNotEmpty()) item("clear") {
            Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
                CapsuleButton("Clear History", { confirmClear = true }, style = ButtonStyle.Destructive,
                    icon = Icons.Rounded.DeleteOutline)
            }
        }
    }
}

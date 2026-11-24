package com.dropflow.android.ui.devices

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dropflow.android.core.model.DeviceStatus
import com.dropflow.android.ui.components.DeviceRow
import com.dropflow.android.ui.components.DiscoveryState
import com.dropflow.android.ui.components.EmptyState
import com.dropflow.android.ui.components.ListCard
import com.dropflow.android.ui.components.Page
import com.dropflow.android.ui.components.cardItems
import com.dropflow.android.ui.components.SearchField
import com.dropflow.android.ui.components.Segmented
import com.dropflow.android.ui.home.HomeViewModel
import com.dropflow.android.ui.navigation.Screen

@Composable
fun DevicesScreen(viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var onlineOnly by rememberSaveable { mutableStateOf(false) }
    val devices = state.discoveredDevices.filter {
        (!onlineOnly || it.status == DeviceStatus.ONLINE) &&
            (it.name.contains(query, true) || it.platform.contains(query, true))
    }.sortedBy { it.name.lowercase() }
    val selected = state.discoveredDevices.firstOrNull {
        it.id == state.selectedDeviceId && it.status == DeviceStatus.ONLINE
    }
    val online = state.discoveredDevices.count { it.status == DeviceStatus.ONLINE }
    Page(Screen.Devices.title, modifier) {
        item("search") { SearchField(query, { query = it }, "Search devices") }
        item("filter") {
            Spacer(Modifier.height(10.dp))
            Segmented(listOf(false to "All · ${state.discoveredDevices.size}", true to "Online · $online"),
                onlineOnly, { onlineOnly = it }, Modifier.fillMaxWidth(), label = "Device filter")
            Spacer(Modifier.height(16.dp))
        }
        when {
            state.discoveredDevices.isEmpty() -> item("discovery") {
                ListCard { DiscoveryState(state.isSearching, state.errorMessage, viewModel::refreshDevices) }
            }
            devices.isEmpty() -> item("no-match") {
                ListCard { EmptyState(Icons.Rounded.Search, "No matches", message = "Try another name or filter.") }
            }
            else -> cardItems(devices, key = { it.id }) { device ->
                DeviceRow(device, selected?.id == device.id, viewModel::selectDevice)
            }
        }
    }
}

package com.dropflow.android.ui.devices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dropflow.android.core.model.DeviceStatus
import com.dropflow.android.ui.components.*
import com.dropflow.android.ui.home.HomeViewModel
import com.dropflow.android.ui.theme.*

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
    val busy = state.outgoingTransferActive || state.activeProgress != null || state.activeSessions.isNotEmpty()
    LazyVerticalGrid(GridCells.Adaptive(240.dp), modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageIntro("LOCAL NETWORK", "Devices", "Find and connect to your nearby devices.")
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(12.dp), placeholder = { Text("Search devices…") },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted) })
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!onlineOnly, { onlineOnly = false }, label = { Text("All · ${state.discoveredDevices.size}") })
                FilterChip(onlineOnly, { onlineOnly = true }, label = {
                    Text("Online · ${state.discoveredDevices.count { it.status == DeviceStatus.ONLINE }}")
                })
            }
        }
        if (state.discoveredDevices.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            DiscoveryEmptyState(state.isSearching, state.errorMessage, viewModel::refreshDevices)
        } else if (devices.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(Icons.Default.Search, "No matching devices", "Try a different name or switch the filter.")
        } else items(devices, key = { it.id }) { device ->
            DeviceCard(device, selected?.id == device.id, viewModel::selectDevice)
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SendFilesCard(selected, busy, viewModel::sendFiles) }
    }
}

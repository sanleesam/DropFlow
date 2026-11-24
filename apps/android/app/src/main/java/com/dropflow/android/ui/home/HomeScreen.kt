package com.dropflow.android.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.dropflow.android.core.model.TransferItem
import com.dropflow.android.core.network.transfer.ServerStatus
import com.dropflow.android.ui.components.DeviceAvatar
import com.dropflow.android.ui.components.DeviceRow
import com.dropflow.android.ui.components.DiscoveryState
import com.dropflow.android.ui.components.EmptyState
import com.dropflow.android.ui.components.ListCard
import com.dropflow.android.ui.components.ListRow
import com.dropflow.android.ui.components.Page
import com.dropflow.android.ui.components.RowDivider
import com.dropflow.android.ui.components.SectionHeader
import com.dropflow.android.ui.components.StatusDot
import com.dropflow.android.ui.components.Tag
import com.dropflow.android.ui.components.TransferRow
import com.dropflow.android.ui.components.sectionGap
import com.dropflow.android.ui.navigation.Screen
import com.dropflow.android.ui.theme.Palette

private const val HOME_DEVICE_LIMIT = 4
private const val HOME_RECENT_LIMIT = 4

@Composable
fun HomeScreen(viewModel: HomeViewModel, recentTransfers: List<TransferItem>,
    onViewDevices: () -> Unit, onViewTransfers: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val colors = Palette
    Page(Screen.Home.title, modifier) {
        item("nearby-head") {
            SectionHeader("Nearby", count = state.discoveredDevices.size,
                action = if (state.discoveredDevices.isNotEmpty()) "Show All" else null, onAction = onViewDevices)
        }
        item("nearby") {
            ListCard {
                val (dot, status) = when (state.serverStatus) {
                    ServerStatus.RUNNING -> colors.green to "Ready to receive"
                    ServerStatus.STARTING -> colors.accent to "Getting ready…"
                    ServerStatus.ERROR -> colors.red to "Receiver unavailable"
                    ServerStatus.STOPPED -> colors.label3 to "Receiver offline"
                }
                ListRow(state.localDeviceName, subtitle = status, subtitleLeading = { StatusDot(dot) },
                    leading = { DeviceAvatar(Icons.Rounded.Smartphone, local = true) },
                    trailing = { Tag("This device") })
                RowDivider()
                if (state.discoveredDevices.isEmpty()) {
                    DiscoveryState(state.isSearching, state.errorMessage, viewModel::refreshDevices)
                } else state.discoveredDevices.take(HOME_DEVICE_LIMIT).forEachIndexed { index, device ->
                    if (index > 0) RowDivider()
                    DeviceRow(device, state.selectedDeviceId == device.id, viewModel::selectDevice)
                }
            }
        }
        sectionGap("gap-recent")
        item("recent-head") {
            SectionHeader("Recent", action = if (recentTransfers.isNotEmpty()) "Show All" else null, onAction = onViewTransfers)
        }
        item("recent") {
            ListCard {
                if (recentTransfers.isEmpty()) {
                    EmptyState(Icons.Rounded.ArrowUpward, "Nothing sent yet", message = "Pick a device, then tap Send.")
                } else recentTransfers.take(HOME_RECENT_LIMIT).forEachIndexed { index, item ->
                    if (index > 0) RowDivider()
                    TransferRow(item)
                }
            }
        }
    }
}

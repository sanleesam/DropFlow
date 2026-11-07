package com.dropflow.android.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.core.model.DeviceStatus
import com.dropflow.android.core.model.TransferItem
import com.dropflow.android.core.network.transfer.ServerStatus
import com.dropflow.android.ui.components.*
import com.dropflow.android.ui.theme.*
import java.util.Calendar

@Composable
fun HomeScreen(viewModel: HomeViewModel, recentTransfers: List<TransferItem>,
    onViewDevices: () -> Unit, onViewTransfers: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val selected = state.discoveredDevices.firstOrNull {
        it.id == state.selectedDeviceId && it.status == DeviceStatus.ONLINE
    }
    val busy = state.outgoingTransferActive || state.activeProgress != null || state.pendingTransferRequest != null
    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning."
        in 12..17 -> "Good afternoon."
        else -> "Good evening."
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageIntro("HOME", greeting, "Send files directly to your nearby devices. No cloud required.") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Icons.Default.Smartphone)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("THIS DEVICE", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                        Text(state.localDeviceName, color = TextPrimary, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val (color, text) = when (state.serverStatus) {
                            ServerStatus.RUNNING -> StatusOnline to "Ready to receive"
                            ServerStatus.STARTING -> IcyBlue to "Getting ready…"
                            ServerStatus.ERROR -> StatusFailed to "Receiver unavailable"
                            ServerStatus.STOPPED -> TextMuted to "Receiver offline"
                        }
                        Text(text, color = color, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.Wifi, null, tint = TextMuted, modifier = Modifier.size(20.dp))
                }
            }
        }
        item { SectionHeading("Nearby devices", "View all", onViewDevices) }
        if (state.discoveredDevices.isEmpty()) item {
            DiscoveryEmptyState(state.isSearching, state.errorMessage, viewModel::refreshDevices)
        } else items(state.discoveredDevices.take(3), key = { it.id }) { device ->
            DeviceCard(device, state.selectedDeviceId == device.id, viewModel::selectDevice)
        }
        item { SendFilesCard(selected, busy, viewModel::sendFiles) }
        item { SectionHeading("Recent activity", "All transfers", onViewTransfers) }
        if (recentTransfers.isEmpty()) item {
            EmptyState(Icons.Default.Description, "No recent transfers", "Sent and received files will appear here.")
        } else items(recentTransfers.take(3), key = { "recent-" + it.id }) { TransferCard(it) }
    }
}

@Composable
private fun SectionHeading(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(action, color = IcyBlue) }
    }
}

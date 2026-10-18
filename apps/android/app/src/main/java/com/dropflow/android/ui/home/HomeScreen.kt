package com.dropflow.android.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.core.model.DeviceStatus
import com.dropflow.android.core.network.transfer.ServerStatus
import com.dropflow.android.ui.components.*
import com.dropflow.android.ui.theme.*

@Composable
fun HomeScreen(viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.sendFiles(uris)
    }
    val selected = state.discoveredDevices.firstOrNull { it.id == state.selectedDeviceId && it.status == DeviceStatus.ONLINE }
    val busy = state.outgoingTransferActive || state.activeProgress != null || state.activeSessions.isNotEmpty()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageIntro("LOCAL. DIRECT. YOURS.", "Share at the speed\nof nearby.", "Your files. Straight to your other devices.") }
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
        item {
            GlassCard(Modifier.fillMaxWidth(), borderColor = if (selected != null) BlueAccentHover else SurfaceGlassBorder) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(Icons.Default.UploadFile)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Send something great.", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(selected?.let { "To ${it.name}" } ?: "Choose a nearby device below.",
                                color = TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    PrimaryAction(if (busy) "Transfer in progress…" else "Choose files", Icons.AutoMirrored.Filled.Send,
                        onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth(),
                        enabled = selected != null && !busy)
                    Text("Direct over Wi-Fi · No cloud · No account", color = TextMuted, fontSize = 11.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Nearby devices", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                StatusPill(if (state.discoveredDevices.isEmpty()) "DISCOVERING" else "${state.discoveredDevices.size} FOUND")
            }
        }
        if (state.discoveredDevices.isEmpty()) item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    DiscoveryRadar(state.isSearching)
                    Text(if (state.errorMessage != null) "Discovery needs a moment" else "Looking for your devices…",
                        color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text("Open DropFlow on another device connected to the same Wi-Fi network.",
                        color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                    if (state.errorMessage != null) Text(state.errorMessage!!, color = StatusFailed,
                        fontSize = 12.sp, textAlign = TextAlign.Center)
                    TextButton(onClick = viewModel::refreshDevices) { Text("Scan again", color = IcyBlue) }
                }
            }
        } else {
            items(state.discoveredDevices, key = { it.id }) { device ->
                DeviceCard(device, state.selectedDeviceId == device.id, viewModel::selectDevice)
            }
        }
    }
}

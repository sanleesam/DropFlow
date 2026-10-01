package com.dropflow.android.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.core.network.transfer.ServerStatus
import com.dropflow.android.core.util.formatBytes
import com.dropflow.android.core.util.formatSpeed
import com.dropflow.android.ui.components.DeviceCard
import com.dropflow.android.ui.components.EmptyState
import com.dropflow.android.ui.components.GlassCard
import com.dropflow.android.ui.components.IncomingTransferBottomSheet
import com.dropflow.android.ui.theme.BackgroundDark
import com.dropflow.android.ui.theme.BlueAccentPrimary
import com.dropflow.android.ui.theme.StatusOnline
import com.dropflow.android.ui.theme.TextPrimary
import com.dropflow.android.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.sendFiles(uris)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // Render Incoming Transfer Bottom Sheet when request is received
    val pendingRequest = uiState.pendingTransferRequest
    if (pendingRequest != null) {
        IncomingTransferBottomSheet(
            request = pendingRequest,
            onAccept = { trustDevice -> viewModel.acceptTransfer(trustDevice) },
            onDecline = { viewModel.declineTransfer() }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Active Transfer Progress Card (Renders live file streaming progress)
        val activeProgress = uiState.activeProgress
        if (activeProgress != null) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(BlueAccentPrimary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sync,
                                        contentDescription = "Transferring",
                                        tint = BlueAccentPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "${if (activeProgress.direction == com.dropflow.android.core.network.transfer.SessionDirection.OUTGOING) "Sending" else "Receiving"} File (${activeProgress.currentFileIndex + 1}/${activeProgress.totalFiles})",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = activeProgress.currentFileName,
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }

                            IconButton(onClick = { viewModel.cancelActiveTransfer() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Linear Progress Bar
                        LinearProgressIndicator(
                            progress = { activeProgress.percentage / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = BlueAccentPrimary,
                            trackColor = BlueAccentPrimary.copy(alpha = 0.15f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${String.format("%.1f", activeProgress.percentage)}%",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${formatBytes(activeProgress.sessionBytesTransferred)} / ${formatBytes(activeProgress.sessionTotalBytes)} · ${formatSpeed(activeProgress.speedBytesPerSec)}",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Local Device Status Card (Shows "Discoverable" with green status indicator)
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(BlueAccentPrimary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Smartphone,
                                contentDescription = "This Device",
                                tint = BlueAccentPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "This Device",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = uiState.localDeviceName,
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val (statusColor, statusText) = when (uiState.serverStatus) {
                            ServerStatus.RUNNING -> StatusOnline to "Discoverable"
                            ServerStatus.STARTING -> BlueAccentPrimary to "Starting…"
                            ServerStatus.ERROR -> com.dropflow.android.ui.theme.StatusFailed to "Transfer error"
                            ServerStatus.STOPPED -> com.dropflow.android.ui.theme.StatusOffline to "Offline"
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Section Title: Nearby Devices (Shows scanning indicator ONLY for Nearby Devices)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Nearby Devices",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (uiState.isSearching) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(BlueAccentPrimary)
                                .alpha(alphaAnim)
                        )
                    }
                }
                Text(
                    text = if (uiState.isSearching && uiState.discoveredDevices.isEmpty()) "Scanning network..." else "${uiState.discoveredDevices.size} active",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // Real Network Discovery State
        if (uiState.discoveredDevices.isEmpty()) {
            item {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    EmptyState(
                        icon = Icons.Default.WifiTethering,
                        title = uiState.errorMessage ?: "Scanning local Wi-Fi network",
                        subtitle = when {
                            uiState.errorMessage != null ->
                                "Make sure DropFlow is open on your other device and both devices are on the same Wi-Fi network, then rescan."
                            else ->
                                "Make sure DropFlow is open on your other device and both devices are on the same Wi-Fi network."
                        },
                        actionLabel = "Rescan",
                        onAction = { viewModel.refreshDevices() }
                    )
                }
            }
        } else {
            items(
                items = uiState.discoveredDevices,
                key = { it.id }
            ) { device ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(animationSpec = tween(250)),
                    exit = fadeOut(animationSpec = tween(250))
                ) {
                    DeviceCard(
                        device = device,
                        isSelected = uiState.selectedDeviceId == device.id,
                        onSelect = { viewModel.selectDevice(it) }
                    )
                }
            }
        }

        // Drop Files Action Target Card
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(BlueAccentPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = "Files",
                            tint = BlueAccentPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (uiState.selectedDeviceId != null) "Ready to send files" else "Select a device to start transfer",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            uiState.outgoingTransferActive -> "Your transfer is in progress."
                            uiState.selectedDeviceId != null -> "Files are sent straight to the selected device — nothing leaves your network."
                            else -> "Select a device above, then pick the files you want to share."
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { documentPicker.launch(arrayOf("*/*")) },
                        enabled = uiState.selectedDeviceId != null && !uiState.outgoingTransferActive,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(if (uiState.outgoingTransferActive) "Transfer in progress…" else "Choose files to send")
                    }
                    uiState.transferResultMessage?.let { message ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = message,
                            color = if (uiState.isTransferResultError) com.dropflow.android.ui.theme.StatusFailed else StatusOnline,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

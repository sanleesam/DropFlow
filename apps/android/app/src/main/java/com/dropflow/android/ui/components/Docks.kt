package com.dropflow.android.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dropflow.android.core.model.Device
import com.dropflow.android.core.network.transfer.SessionDirection
import com.dropflow.android.core.network.transfer.TransferPhase
import com.dropflow.android.core.network.transfer.TransferProgressPayload
import com.dropflow.android.core.util.formatBytes
import com.dropflow.android.core.util.formatSpeed
import com.dropflow.android.ui.glass.Glass
import com.dropflow.android.ui.theme.DropFlowType
import com.dropflow.android.ui.theme.Palette

private val DockShape = RoundedCornerShape(30.dp)

/** Floating dock: where you're sending, and the one primary action. */
@Composable
fun SendDock(selected: Device?, busy: Boolean, onFilesSelected: (List<Uri>) -> Unit, modifier: Modifier = Modifier) {
    val colors = Palette
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onFilesSelected(uris)
    }
    Glass(modifier.fillMaxWidth().height(60.dp), shape = DockShape, cornerRadius = 30.dp) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selected != null) DeviceAvatar(deviceIcon(selected.type), size = 36.dp)
            else IconTile(Icons.Rounded.TouchApp, tone = Tone.Neutral, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(when {
                    busy -> "Transfer in progress"
                    selected != null -> selected.name
                    else -> "Choose a device"
                }, style = DropFlowType.callout, fontWeight = FontWeight.SemiBold, color = colors.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    busy -> "Files stay on your network"
                    selected != null -> selected.platform
                    else -> "Tap a nearby device"
                },
                    style = DropFlowType.caption, color = colors.label2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CapsuleButton("Send", { picker.launch(arrayOf("*/*")) }, style = ButtonStyle.Accent,
                icon = Icons.Rounded.ArrowUpward, enabled = selected != null && !busy)
        }
    }
}

/** Live transfer, floating above every page until it finishes. */
@Composable
fun TransferDock(progress: TransferProgressPayload, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Palette
    val sending = progress.direction == SessionDirection.OUTGOING
    val percent = progress.percentage.toInt().coerceIn(0, 100)
    val status = when (progress.phase) {
        TransferPhase.PREPARING -> if (sending) "Waiting for approval" else "Preparing"
        TransferPhase.STREAMING -> "${progress.currentFileIndex + 1} of ${progress.totalFiles} · " +
            "${formatBytes(progress.sessionBytesTransferred)} of ${formatBytes(progress.sessionTotalBytes)} · " +
            formatSpeed(progress.speedBytesPerSec)
        TransferPhase.FINISHING -> if (sending) "Waiting for the recipient to confirm" else "Verifying and saving"
    }
    Glass(modifier.fillMaxWidth(), shape = DockShape, cornerRadius = 30.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 13.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(if (sending) Icons.Rounded.NorthEast else Icons.Rounded.SouthWest, tone = Tone.Accent, size = 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(progress.currentFileName, style = DropFlowType.callout, fontWeight = FontWeight.SemiBold,
                        color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status, style = DropFlowType.caption, color = colors.label2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (progress.phase == TransferPhase.STREAMING)
                    Text("$percent%", style = DropFlowType.footnote, fontWeight = FontWeight.SemiBold, color = colors.label2)
                IconCircleButton(Icons.Rounded.Close, "Cancel transfer", onCancel, size = 34.dp,
                    tint = colors.label2, container = colors.fill)
            }
            ProgressBar(percent / 100f, Modifier.padding(end = 4.dp))
        }
    }
}

/** Transient result message. */
@Composable
fun GlassToast(message: String, error: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Palette
    Glass(modifier, shape = RoundedCornerShape(26.dp), cornerRadius = 26.dp) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp).height(52.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (error) Icons.Rounded.Error else Icons.Rounded.CheckCircle, null,
                tint = if (error) colors.red else colors.green, modifier = Modifier.size(20.dp))
            Text(message, style = DropFlowType.subhead, color = colors.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            IconCircleButton(Icons.Rounded.Close, "Dismiss", onDismiss, size = 36.dp, tint = colors.label2)
        }
    }
}

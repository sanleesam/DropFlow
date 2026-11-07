package com.dropflow.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.core.network.transfer.*
import com.dropflow.android.core.util.*
import com.dropflow.android.ui.theme.*

@Composable
fun ActiveTransferCard(progress: TransferProgressPayload, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val sending = progress.direction == SessionDirection.OUTGOING
    val phase = when (progress.phase) {
        TransferPhase.PREPARING -> if (sending) "Awaiting approval" else "Preparing"
        TransferPhase.STREAMING -> "${if (sending) "Sending" else "Receiving"} · ${progress.currentFileIndex + 1} of ${progress.totalFiles}"
        TransferPhase.FINISHING -> "Finishing"
    }
    GlassCard(modifier.fillMaxWidth(), borderColor = BlueAccentHover) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(phase,
                        color = IcyBlue, fontSize = 11.sp)
                    Text(progress.currentFileName, color = TextPrimary, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel transfer", tint = TextSecondary) }
            }
            LinearProgressIndicator(progress = { (progress.percentage / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = BlueAccentHover, trackColor = ProgressTrack)
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(when (progress.phase) {
                    TransferPhase.PREPARING -> if (sending) "Keep both devices open" else "Waiting for the first file"
                    TransferPhase.STREAMING -> "${progress.percentage.toInt().coerceIn(0, 100)}% · ${formatBytes(progress.sessionBytesTransferred)} / ${formatBytes(progress.sessionTotalBytes)}"
                    TransferPhase.FINISHING -> if (sending) "Waiting for the recipient to confirm" else "Verifying and saving files"
                },
                    color = TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (progress.phase == TransferPhase.STREAMING) {
                    Text(formatSpeed(progress.speedBytesPerSec), color = IcyBlue, fontSize = 11.sp)
                }
            }
        }
    }
}

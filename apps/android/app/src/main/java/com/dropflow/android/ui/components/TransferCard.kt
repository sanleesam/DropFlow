package com.dropflow.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.core.model.*
import com.dropflow.android.ui.theme.*

@Composable
fun TransferCard(transfer: TransferItem, modifier: Modifier = Modifier) {
    val receive = transfer.direction == TransferDirection.RECEIVE
    val (color, status) = when (transfer.status) {
        TransferStatus.COMPLETED -> StatusOnline to "Completed"
        TransferStatus.FAILED -> StatusFailed to "Failed"
        TransferStatus.CANCELLED -> TextMuted to "Cancelled"
        TransferStatus.IN_PROGRESS -> IcyBlue to "In progress"
    }
    GlassCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(if (receive) Icons.Default.SouthWest else Icons.Default.NorthEast,
                    tint = if (receive) StatusOnline else IcyBlue)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(transfer.fileName, color = TextPrimary, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${if (receive) "From" else "To"} ${transfer.deviceName}", color = TextSecondary,
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(transfer.size, color = TextSecondary, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(transfer.timestamp, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                StatusPill(status, color)
            }
            transfer.error?.let { Text(it, color = StatusFailed, fontSize = 12.sp, lineHeight = 18.sp) }
        }
    }
}

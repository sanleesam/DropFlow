package com.dropflow.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.core.model.TransferDirection
import com.dropflow.android.core.model.TransferItem
import com.dropflow.android.core.model.TransferStatus
import com.dropflow.android.ui.theme.BlueAccentPrimary
import com.dropflow.android.ui.theme.StatusFailed
import com.dropflow.android.ui.theme.StatusOnline
import com.dropflow.android.ui.theme.TextPrimary
import com.dropflow.android.ui.theme.TextSecondary

@Composable
fun TransferCard(
    transfer: TransferItem,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        content = {
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
                    val isReceive = transfer.direction == TransferDirection.RECEIVE
                    val directionIcon = if (isReceive) Icons.Default.SouthWest else Icons.Default.NorthEast
                    val iconTint = if (isReceive) StatusOnline else BlueAccentPrimary

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(iconTint.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = directionIcon,
                            contentDescription = if (isReceive) "Received" else "Sent",
                            tint = iconTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column {
                        Text(
                            text = transfer.fileName,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "${if (isReceive) "Received from" else "Sent to"} ${transfer.deviceName} • ${transfer.timestamp}",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = transfer.size,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = when (transfer.status) {
                            TransferStatus.COMPLETED -> "Completed"
                            TransferStatus.IN_PROGRESS -> "Transferring..."
                            TransferStatus.FAILED -> "Failed"
                            TransferStatus.CANCELLED -> "Cancelled"
                        },
                        color = when (transfer.status) {
                            TransferStatus.COMPLETED -> StatusOnline
                            TransferStatus.IN_PROGRESS -> BlueAccentPrimary
                            TransferStatus.FAILED -> StatusFailed
                            TransferStatus.CANCELLED -> TextSecondary
                        },
                        fontSize = 10.sp
                    )
                }
            }
        }
    )
}

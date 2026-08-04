package com.dropflow.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.core.model.Device
import com.dropflow.android.core.model.DeviceStatus
import com.dropflow.android.core.model.DeviceType
import com.dropflow.android.ui.theme.BlueAccentPrimary
import com.dropflow.android.ui.theme.BlueAccentSoft
import com.dropflow.android.ui.theme.StatusOffline
import com.dropflow.android.ui.theme.StatusOnline
import com.dropflow.android.ui.theme.StatusRecentlySeen
import com.dropflow.android.ui.theme.SurfaceGlassBorder
import com.dropflow.android.ui.theme.TextPrimary
import com.dropflow.android.ui.theme.TextSecondary

@Composable
fun DeviceCard(
    device: Device,
    isSelected: Boolean = false,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) BlueAccentPrimary else SurfaceGlassBorder
    val backgroundColor = if (isSelected) BlueAccentSoft else SurfaceGlassBorder.copy(alpha = 0.05f)

    val icon: ImageVector = when (device.type) {
        DeviceType.DESKTOP -> Icons.Default.Computer
        DeviceType.LAPTOP -> Icons.Default.Laptop
        DeviceType.TABLET -> Icons.Default.Tablet
        DeviceType.PHONE -> Icons.Default.Smartphone
    }

    val (statusColor, statusText) = when (device.status) {
        DeviceStatus.ONLINE -> StatusOnline to "Online"
        DeviceStatus.RECENTLY_SEEN -> StatusRecentlySeen to "Recently Seen"
        DeviceStatus.OFFLINE -> StatusOffline to "Offline"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onSelect(device.id) }
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                        imageVector = icon,
                        contentDescription = device.type.name,
                        tint = BlueAccentPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = device.name,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${device.platform} · ${device.type.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

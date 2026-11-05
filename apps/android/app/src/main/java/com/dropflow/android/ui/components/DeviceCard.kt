package com.dropflow.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.core.model.*
import com.dropflow.android.ui.theme.*

@Composable
fun DeviceCard(device: Device, isSelected: Boolean = false, onSelect: (String) -> Unit,
               modifier: Modifier = Modifier) {
    val border by animateColorAsState(if (isSelected) BlueAccentHover else SurfaceGlassBorder,
        animationSpec = tween(if (LocalReducedMotion.current) 0 else 160), label = "selection")
    val icon = when (device.type) {
        DeviceType.DESKTOP -> Icons.Default.Computer
        DeviceType.LAPTOP -> Icons.Default.Laptop
        DeviceType.TABLET -> Icons.Default.Tablet
        DeviceType.PHONE -> Icons.Default.Smartphone
    }
    val (color, status) = when (device.status) {
        DeviceStatus.ONLINE -> StatusOnline to "Online"
        DeviceStatus.RECENTLY_SEEN -> StatusRecentlySeen to "Recently seen"
        DeviceStatus.OFFLINE -> StatusOffline to "Offline"
    }
    GlassCard(modifier.fillMaxWidth(), borderColor = border,
        backgroundColor = if (isSelected) androidx.compose.ui.graphics.Color(0xFF1B2D45) else SurfaceGlass) {
        Row(Modifier.fillMaxWidth().selectable(selected = isSelected,
            enabled = device.status == DeviceStatus.ONLINE, role = Role.RadioButton,
            onClick = { onSelect(device.id) }).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(device.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${device.platform} · $status", color = color, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isSelected) "Selected" else null,
                tint = if (isSelected) IcyBlue else TextMuted, modifier = Modifier.size(22.dp))
        }
    }
}

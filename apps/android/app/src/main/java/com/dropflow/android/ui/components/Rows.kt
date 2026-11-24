package com.dropflow.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dropflow.android.core.model.Device
import com.dropflow.android.core.model.DeviceStatus
import com.dropflow.android.core.model.TransferDirection
import com.dropflow.android.core.model.TransferItem
import com.dropflow.android.core.model.TransferStatus
import com.dropflow.android.ui.theme.DropFlowType
import com.dropflow.android.ui.theme.Motion
import com.dropflow.android.ui.theme.Palette
import com.dropflow.android.ui.theme.Shapes

@Composable
fun DeviceRow(device: Device, selected: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = Palette
    val (dot, status) = when (device.status) {
        DeviceStatus.ONLINE -> colors.green to "Online"
        DeviceStatus.RECENTLY_SEEN -> colors.orange to "Recently seen"
        DeviceStatus.OFFLINE -> colors.label3 to "Offline"
    }
    val online = device.status == DeviceStatus.ONLINE
    ListRow(device.name, modifier,
        subtitle = "${device.platform} · $status",
        subtitleLeading = { StatusDot(dot) },
        titleColor = if (online) colors.label else colors.label2,
        leading = { DeviceAvatar(deviceIcon(device.type), selected = selected) },
        trailing = if (selected) {
            { Icon(Icons.Rounded.Check, "Selected", tint = colors.accent, modifier = Modifier.size(22.dp)) }
        } else null,
        selected = selected, enabled = online, onClick = { onSelect(device.id) })
}

@Composable
fun TransferRow(transfer: TransferItem, modifier: Modifier = Modifier) {
    val colors = Palette
    val receive = transfer.direction == TransferDirection.RECEIVE
    val (tone, tag) = when (transfer.status) {
        TransferStatus.COMPLETED -> Tone.Green to null
        TransferStatus.FAILED -> Tone.Red to "Failed"
        TransferStatus.CANCELLED -> Tone.Neutral to "Cancelled"
        TransferStatus.IN_PROGRESS -> Tone.Accent to "In progress"
    }
    ListRow(transfer.fileName, modifier,
        subtitle = "${if (receive) "From" else "To"} ${transfer.deviceName} · ${transfer.size}",
        leading = { IconTile(if (receive) Icons.Rounded.SouthWest else Icons.Rounded.NorthEast, tone = tone) },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(transfer.timestamp, style = DropFlowType.caption, color = colors.label3, maxLines = 1)
                if (tag != null) Tag(tag, tone = tone)
            }
        },
        titleMaxLines = 2, detail = transfer.error)
}

/** Shown in place of the device list while nothing has been found. */
@Composable
fun DiscoveryState(searching: Boolean, error: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Palette
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Pulse(searching && error == null)
        Text(if (error == null) "Looking for devices" else "Discovery unavailable",
            style = DropFlowType.headline, color = colors.label, textAlign = TextAlign.Center)
        Text(error ?: "Open DropFlow on another device on this Wi‑Fi.",
            style = DropFlowType.footnote, color = if (error == null) colors.label2 else colors.red, textAlign = TextAlign.Center)
        Box(Modifier.padding(top = 6.dp)) { CapsuleButton("Search Again", onRetry) }
    }
}

@Composable
private fun Pulse(active: Boolean) {
    val colors = Palette
    val animate = active && Motion.enabled
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "discovery")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "rings")
        value
    } else 0.5f
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(84.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            for (index in 0..2) {
                val p = (phase + index / 3f) % 1f
                drawCircle(colors.accent.copy(alpha = (1 - p) * 0.28f), radius = size.minDimension * (0.24f + p * 0.26f),
                    center = center, style = Stroke(1.dp.toPx()))
            }
        }
        Box(Modifier.size(40.dp).background(colors.accentFill, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.WifiTethering, null, tint = colors.accent, modifier = Modifier.size(21.dp))
        }
    }
}

/** Search capsule on content (a fill, not glass). */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val colors = Palette
    Row(modifier.fillMaxWidth().height(40.dp).background(colors.fill, Shapes.Capsule).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.Search, null, tint = colors.label2, modifier = Modifier.size(19.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = DropFlowType.body, color = colors.label3, maxLines = 1)
            BasicTextField(value, onValueChange, singleLine = true,
                textStyle = DropFlowType.body.copy(color = colors.label), cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder })
        }
        if (value.isNotEmpty()) IconCircleButton(Icons.Rounded.Cancel, "Clear search", { onValueChange("") },
            size = 32.dp, tint = colors.label3)
    }
}

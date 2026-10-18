package com.dropflow.android.ui.components

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dropflow.android.ui.theme.*

@Composable
fun DiscoveryRadar(searching: Boolean) {
    val context = LocalContext.current
    val systemReduced = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val animate = searching && !LocalReducedMotion.current && !systemReduced
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "discovery")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "rings")
        value
    } else 0.5f
    Box(Modifier.fillMaxWidth().height(126.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(126.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            for (index in 0..2) {
                val p = (phase + index / 3f) % 1f
                drawCircle(IcyBlue.copy(alpha = (1 - p) * 0.23f),
                    radius = size.minDimension * (0.20f + p * 0.29f), center = center, style = Stroke(1.dp.toPx()))
            }
            drawCircle(BlueAccentPrimary.copy(alpha = 0.08f), size.minDimension * 0.46f, center)
        }
        IconTile(Icons.Default.WifiTethering, Modifier.size(48.dp))
    }
}

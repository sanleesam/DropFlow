package com.dropflow.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.ui.theme.*

@Composable
fun DiscoveryEmptyState(searching: Boolean, error: String?, onRetry: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DiscoveryRadar(searching && error == null)
            Text(if (error == null) "Looking for nearby devices" else "Discovery unavailable",
                color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center)
            Text("Open DropFlow on another device connected to the same local network.",
                color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
            if (error != null) Text(error, color = StatusFailed, fontSize = 12.sp, textAlign = TextAlign.Center)
            OutlinedButton(onRetry) { Text("Search again", color = IcyBlue) }
        }
    }
}

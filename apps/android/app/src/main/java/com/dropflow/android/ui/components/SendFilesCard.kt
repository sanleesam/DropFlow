package com.dropflow.android.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.core.model.Device
import com.dropflow.android.ui.theme.*

@Composable
fun SendFilesCard(selected: Device?, busy: Boolean, onFilesSelected: (List<Uri>) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onFilesSelected(uris)
    }
    GlassCard(Modifier.fillMaxWidth(), borderColor = if (selected != null) BlueAccentHover else SurfaceGlassBorder) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Send files", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Any file type", color = TextMuted, fontSize = 12.sp)
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(Icons.Default.FolderOpen)
                Text(selected?.let { "Send to ${it.name}" } ?: "Choose a nearby device",
                    color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text("Files stay on your local network.", color = TextSecondary, fontSize = 13.sp,
                    textAlign = TextAlign.Center)
            }
            PrimaryAction(if (busy) "Transfer in progress…" else "Choose files",
                Icons.AutoMirrored.Filled.Send, onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(), enabled = selected != null && !busy)
        }
    }
}

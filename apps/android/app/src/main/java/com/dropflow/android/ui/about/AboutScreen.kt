package com.dropflow.android.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.R
import com.dropflow.android.ui.components.*
import com.dropflow.android.ui.theme.*

@Composable
fun AboutScreen(viewModel: AboutViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageIntro("ANDROID", "About DropFlow", "Send files. Simply.") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.ic_dropflow_logo), "DropFlow", Modifier.size(96.dp))
                    Text("DropFlow", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
                    StatusPill("ANDROID · ${state.appVersion}")
                    Text(state.buildChannel, color = TextMuted, fontSize = 12.sp)
                }
            }
        }
        item { AboutFeature(Icons.Default.Wifi, "Your network. Your files.",
            "Send directly to DropFlow on Android, macOS and Windows over the same Wi-Fi. No accounts or cloud uploads.") }
        item { AboutFeature(Icons.Default.VerifiedUser, "Checked on arrival.",
            "Incoming files are verified before they appear in Downloads / DropFlow. You choose which transfers to accept.") }
        item { Text("Local-first sharing. Thoughtfully simple.", color = TextMuted, fontSize = 12.sp) }
    }
}

@Composable
private fun AboutFeature(icon: ImageVector, title: String, description: String) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = TextSecondary, fontSize = 13.sp, lineHeight = 21.sp)
            }
        }
    }
}

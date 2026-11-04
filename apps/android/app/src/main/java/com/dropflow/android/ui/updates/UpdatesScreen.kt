package com.dropflow.android.ui.updates

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import com.dropflow.android.BuildConfig
import com.dropflow.android.core.update.AndroidUpdateChannel
import com.dropflow.android.ui.components.*
import com.dropflow.android.ui.theme.*
import java.text.DateFormat
import java.util.Date

@Composable
fun UpdatesScreen(viewModel: UpdatesViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val busy = state.phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageIntro("SETTINGS", "Updates", "Choose your release channel and keep DropFlow up to date.") }
        item { GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Installed version", color = TextSecondary, fontSize = 13.sp)
                Text(BuildConfig.VERSION_NAME, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                if (BuildConfig.DEBUG) Text("Debug preview · published updates use the release signing key.", color = TextMuted, fontSize = 12.sp)
            }
        } }
        item { Text("Update channel", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        items(AndroidUpdateChannel.entries.size) { index ->
            val channel = AndroidUpdateChannel.entries[index]
            GlassCard(Modifier.fillMaxWidth(), borderColor = if (channel == state.channel) BlueAccentHover else SurfaceGlassBorder) {
                Row(Modifier.fillMaxWidth().selectable(channel == state.channel, enabled = !busy,
                    role = Role.RadioButton, onClick = { viewModel.selectChannel(channel) }).padding(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(channel == state.channel, null, enabled = !busy)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(channel.title, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text(channel.description, color = TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
        item { GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val title = when (state.phase) {
                    UpdatePhase.IDLE -> "Ready to check"
                    UpdatePhase.CHECKING -> "Checking for updates…"
                    UpdatePhase.CURRENT -> "No newer ${state.channel.title} update"
                    UpdatePhase.UNAVAILABLE -> "No ${state.channel.title} release published yet"
                    UpdatePhase.AVAILABLE -> "${state.manifest?.version} is available"
                    UpdatePhase.DOWNLOADING -> "Downloading update…"
                    UpdatePhase.READY -> "Verified and ready to install"
                    UpdatePhase.VERIFYING -> "Rechecking verified update…"
                    UpdatePhase.PERMISSION_REQUIRED -> "Android needs your approval"
                    UpdatePhase.INSTALLER_OPEN -> "Finish in the Android installer"
                    UpdatePhase.ERROR -> "Could not complete the update"
                }
                Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                state.message?.let { Text(it, color = if (state.phase == UpdatePhase.ERROR) StatusFailed else TextSecondary, fontSize = 13.sp) }
                state.checkedAt?.let { Text("Last checked ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}",
                    color = TextMuted, fontSize = 12.sp) }
                when (state.phase) {
                    UpdatePhase.DOWNLOADING -> {
                        val progress = state.downloaded.toFloat() / (state.manifest?.size ?: 1).coerceAtLeast(1)
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = IcyBlue, trackColor = ProgressTrack)
                        Text("${(progress * 100).toInt()}% · verified before installation", color = TextSecondary, fontSize = 12.sp)
                        OutlinedButton(viewModel::cancelDownload) { Text("Cancel download") }
                    }
                    UpdatePhase.AVAILABLE -> PrimaryAction("Download update", Icons.Default.Download, viewModel::download, Modifier.fillMaxWidth())
                    UpdatePhase.ERROR -> {
                        if (state.manifest != null && state.manifest!!.isNewerThan(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()))
                            PrimaryAction("Retry download", Icons.Default.Download, viewModel::download, Modifier.fillMaxWidth())
                        else PrimaryAction("Try again", Icons.Default.Refresh, viewModel::check, Modifier.fillMaxWidth())
                    }
                    UpdatePhase.READY, UpdatePhase.INSTALLER_OPEN -> PrimaryAction("Install update", Icons.Default.SystemUpdate,
                        { viewModel.install(context) }, Modifier.fillMaxWidth())
                    UpdatePhase.PERMISSION_REQUIRED -> {
                        Text("Allow DropFlow to request installation in Android settings, then return and tap Install. Android will ask you to confirm the update.",
                            color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                        OutlinedButton({ viewModel.openInstallSettings(context) }) { Text("Open install permission settings") }
                        PrimaryAction("Install update", Icons.Default.SystemUpdate, { viewModel.install(context) }, Modifier.fillMaxWidth())
                    }
                    else -> PrimaryAction(if (busy) "Please wait…" else "Check for updates",
                        Icons.Default.Refresh, viewModel::check, Modifier.fillMaxWidth(), enabled = !busy)
                }
                if (!busy && state.phase in listOf(UpdatePhase.AVAILABLE, UpdatePhase.READY, UpdatePhase.ERROR)) {
                    TextButton(viewModel::check) { Text("Check again", color = IcyBlue) }
                }
            }
        } }
        state.manifest?.notes?.takeIf { it.isNotBlank() }?.let { notes -> item {
            GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("What's new", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(notes, color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            } }
        } }
        item { Text("Checks GitHub only when you ask. Updates never install silently. Your file transfers stay on your local network.",
            color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp) }
    }
}

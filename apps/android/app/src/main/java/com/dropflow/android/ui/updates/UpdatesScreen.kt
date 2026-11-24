package com.dropflow.android.ui.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dropflow.android.core.update.AndroidUpdateChannel
import com.dropflow.android.ui.components.ButtonStyle
import com.dropflow.android.ui.components.CapsuleButton
import com.dropflow.android.ui.components.ListCard
import com.dropflow.android.ui.components.ListRow
import com.dropflow.android.ui.components.Page
import com.dropflow.android.ui.components.ProgressBar
import com.dropflow.android.ui.components.RowDivider
import com.dropflow.android.ui.components.SectionFooter
import com.dropflow.android.ui.components.SectionHeader
import com.dropflow.android.ui.components.sectionGap
import com.dropflow.android.ui.navigation.Screen
import com.dropflow.android.ui.theme.DropFlowType
import com.dropflow.android.ui.theme.Palette
import java.text.DateFormat
import java.util.Date

@Composable
fun UpdatesScreen(viewModel: UpdatesViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val release = viewModel.installedRelease
    val context = LocalContext.current
    val colors = Palette
    val busy = state.phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
    Page(Screen.Updates.title, modifier) {
        item("installed") {
            ListCard {
                ListRow("Installed", trailing = {
                    Text(release.versionName, style = DropFlowType.body, color = colors.label2)
                })
            }
        }
        if (release.debuggable) item("debug") {
            SectionFooter("Debug build · published updates use the release key.", Modifier.padding(top = 6.dp))
        }
        sectionGap("gap-channel")
        item("channel-head") { SectionHeader("Channel") }
        item("channels") {
            ListCard {
                AndroidUpdateChannel.entries.forEachIndexed { index, channel ->
                    if (index > 0) RowDivider(12.dp)
                    val active = channel == state.channel
                    ListRow(channel.title, subtitle = channel.description, selected = active, enabled = !busy,
                        onClick = { viewModel.selectChannel(channel) },
                        trailing = if (active) {
                            { Icon(Icons.Rounded.Check, "Selected", tint = colors.accent, modifier = Modifier.size(22.dp)) }
                        } else null)
                }
            }
        }
        sectionGap("gap-status")
        item("status") {
            ListCard {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val title = when (state.phase) {
                        UpdatePhase.IDLE -> "Ready to check"
                        UpdatePhase.CHECKING -> "Checking…"
                        UpdatePhase.CURRENT -> "You're up to date on ${state.channel.title}"
                        UpdatePhase.UNAVAILABLE -> "No ${state.channel.title} release yet"
                        UpdatePhase.AVAILABLE -> "${state.manifest?.version} is available"
                        UpdatePhase.DOWNLOADING -> "Downloading…"
                        UpdatePhase.READY -> "Verified and ready to install"
                        UpdatePhase.VERIFYING -> "Rechecking verified update…"
                        UpdatePhase.PERMISSION_REQUIRED -> "Android needs your approval"
                        UpdatePhase.INSTALLER_OPEN -> "Finish in the Android installer"
                        UpdatePhase.ERROR -> "Couldn't complete the update"
                    }
                    Text(title, style = DropFlowType.headline, color = colors.label)
                    state.message?.let {
                        Text(it, style = DropFlowType.footnote, color = if (state.phase == UpdatePhase.ERROR) colors.red else colors.label2)
                    }
                    state.checkedAt?.let {
                        Text("Last checked ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}",
                            style = DropFlowType.caption, color = colors.label3)
                    }
                    when (state.phase) {
                        UpdatePhase.DOWNLOADING -> {
                            val progress = state.downloaded.toFloat() / (state.manifest?.size ?: 1).coerceAtLeast(1)
                            ProgressBar(progress)
                            Text("${(progress * 100).toInt()}% · verified before installing", style = DropFlowType.caption,
                                color = colors.label2)
                            CapsuleButton("Cancel Download", viewModel::cancelDownload)
                        }
                        UpdatePhase.AVAILABLE -> CapsuleButton("Download Update", viewModel::download, Modifier.fillMaxWidth(),
                            ButtonStyle.Accent, Icons.Rounded.Download, large = true)
                        UpdatePhase.ERROR -> {
                            if (state.manifest?.isNewerThan(release.versionName, release.versionCode) == true)
                                CapsuleButton("Retry Download", viewModel::download, Modifier.fillMaxWidth(),
                                    ButtonStyle.Accent, Icons.Rounded.Download, large = true)
                            else CapsuleButton("Try Again", viewModel::check, Modifier.fillMaxWidth(),
                                ButtonStyle.Accent, Icons.Rounded.Refresh, large = true)
                        }
                        UpdatePhase.READY, UpdatePhase.INSTALLER_OPEN -> CapsuleButton("Install Update",
                            { viewModel.install(context) }, Modifier.fillMaxWidth(), ButtonStyle.Accent,
                            Icons.Rounded.SystemUpdate, large = true)
                        UpdatePhase.PERMISSION_REQUIRED -> {
                            Text("Allow installs from DropFlow, then come back and tap Install.",
                                style = DropFlowType.footnote, color = colors.label2)
                            CapsuleButton("Open Install Settings", { viewModel.openInstallSettings(context) }, Modifier.fillMaxWidth())
                            CapsuleButton("Install Update", { viewModel.install(context) }, Modifier.fillMaxWidth(),
                                ButtonStyle.Accent, Icons.Rounded.SystemUpdate, large = true)
                        }
                        else -> CapsuleButton(if (busy) "Please wait…" else "Check for Updates", viewModel::check,
                            Modifier.fillMaxWidth(), ButtonStyle.Accent, Icons.Rounded.Refresh, enabled = !busy, large = true)
                    }
                    if (!busy && state.phase in listOf(UpdatePhase.AVAILABLE, UpdatePhase.READY, UpdatePhase.ERROR)) {
                        CapsuleButton("Check Again", viewModel::check, style = ButtonStyle.Plain)
                    }
                }
            }
        }
        state.manifest?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            sectionGap("gap-notes")
            item("notes-head") { SectionHeader("What's New") }
            item("notes") {
                ListCard {
                    Text(notes, style = DropFlowType.subhead, color = colors.label2, modifier = Modifier.padding(10.dp))
                }
            }
        }
        item("foot") {
            SectionFooter("Checks GitHub only when you ask. Never installs silently.", Modifier.padding(top = 18.dp),
                color = colors.label3)
        }
    }
}

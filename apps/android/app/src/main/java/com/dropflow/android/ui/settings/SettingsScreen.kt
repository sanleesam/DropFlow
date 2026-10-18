package com.dropflow.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.dropflow.android.ui.components.*
import com.dropflow.android.ui.theme.*

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    var draft by rememberSaveable(state.deviceName) { mutableStateOf(state.deviceName) }
    val keyboard = LocalSoftwareKeyboardController.current
    val canSave = draft.trim().isNotEmpty() && draft.trim() != state.deviceName
    val save = { if (canSave) { viewModel.updateDeviceName(draft); keyboard?.hide() }; Unit }
    LazyColumn(modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageIntro("MAKE IT YOURS", "A little more you.", "Thoughtful controls for the way you share.") }
        item {
            SettingsGroup(Icons.Default.Badge, "Device identity") {
                Text("This is how you appear on nearby devices.", color = TextSecondary, fontSize = 13.sp)
                OutlinedTextField(value = draft, onValueChange = { if (it.length <= 60) draft = it },
                    label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    supportingText = { if (draft.isBlank()) Text("Enter a name to save.") }, isError = draft.isBlank())
                PrimaryAction("Save name", Icons.Default.Check, save, enabled = canSave)
            }
        }
        item {
            SettingsGroup(Icons.Default.Shield, "Receiving files") {
                SettingSwitch("Ask before receiving", "Review each incoming transfer before it starts.",
                    state.requireConfirmation, viewModel::updateRequireConfirmation)
                HorizontalDivider(color = SurfaceGlassBorder)
                SettingSwitch("Auto-accept trusted devices", "Trust applies during this app session.",
                    state.autoAcceptTrustedDevices, viewModel::updateAutoAcceptTrusted,
                    enabled = state.requireConfirmation)
            }
        }
        item {
            SettingsGroup(Icons.Default.FolderOpen, "Downloads") {
                StatusPill("Downloads / DropFlow")
                Text("Received files appear here after verification. Files with the same name are saved as separate copies.",
                    color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
        item {
            SettingsGroup(Icons.Default.Palette, "Appearance") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusPill("TITANIUM")
                    Text("Icy Blue", color = IcyBlue, fontSize = 13.sp)
                }
                Text("The same cool graphite and reflective silver as DropFlow on desktop.",
                    color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                SettingSwitch("Reduce animations", "Keep discovery calm and still.", state.reduceMotion, viewModel::updateReduceMotion)
            }
        }
        item {
            Text("Keep DropFlow open while sharing. Devices must be connected to the same Wi-Fi network.",
                color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun SettingsGroup(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = IcyBlue, modifier = Modifier.size(20.dp))
                Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean,
    onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(description, color = TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Switch(checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.minimumInteractiveComponentSize().semantics { contentDescription = title },
            colors = SwitchDefaults.colors(checkedThumbColor = BackgroundDark, checkedTrackColor = IcyBlue))
    }
}

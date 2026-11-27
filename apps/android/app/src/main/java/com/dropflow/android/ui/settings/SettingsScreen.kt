package com.dropflow.android.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dropflow.android.BuildConfig
import com.dropflow.android.core.network.transfer.DirectLinkSupport
import com.dropflow.android.ui.components.ButtonStyle
import com.dropflow.android.ui.components.CapsuleButton
import com.dropflow.android.ui.components.IconTile
import com.dropflow.android.ui.components.ListCard
import com.dropflow.android.ui.components.ListRow
import com.dropflow.android.ui.components.Page
import com.dropflow.android.ui.components.RowDivider
import com.dropflow.android.ui.components.SectionFooter
import com.dropflow.android.ui.components.SectionHeader
import com.dropflow.android.ui.components.Segmented
import com.dropflow.android.ui.components.Tag
import com.dropflow.android.ui.components.ToggleSwitch
import com.dropflow.android.ui.components.Tone
import com.dropflow.android.ui.components.sectionGap
import com.dropflow.android.ui.glass.GlassMode
import com.dropflow.android.ui.glass.GlassStyle
import com.dropflow.android.ui.glass.LocalGlassMode
import com.dropflow.android.ui.navigation.Screen
import com.dropflow.android.ui.theme.DropFlowType
import com.dropflow.android.ui.theme.Palette
import com.dropflow.android.ui.theme.ThemeMode

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onAbout: () -> Unit, onUpdates: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    var draft by rememberSaveable(state.deviceName) { mutableStateOf(state.deviceName) }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val colors = Palette
    val glassMode = LocalGlassMode.current
    val canSave = draft.trim().isNotEmpty() && draft.trim() != state.deviceName
    val save = { if (canSave) { viewModel.updateDeviceName(draft); keyboard?.hide() }; Unit }
    // Joining must leave this phone's own Wi-Fi connected; not every phone can do that.
    val canLink = remember(context) { DirectLinkSupport.keepsWifi(context) }
    Page(Screen.Settings.title, modifier) {
        item("device-head") { SectionHeader("This Device") }
        item("device") {
            ListCard {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Name", style = DropFlowType.body, fontWeight = FontWeight.Medium, color = colors.label)
                    BasicTextField(draft, { if (it.length <= 60) draft = it }, singleLine = true,
                        textStyle = DropFlowType.body.copy(color = colors.label2, textAlign = TextAlign.End),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Device name" })
                    if (canSave) CapsuleButton("Save", save, style = ButtonStyle.Accent)
                }
            }
        }
        item("device-foot") {
            SectionFooter(if (draft.isBlank()) "Enter a name to save." else "Shown to nearby devices.",
                Modifier.padding(top = 6.dp), color = if (draft.isBlank()) colors.red else colors.label2)
        }
        sectionGap("gap-receiving")
        item("receiving-head") { SectionHeader("Receiving") }
        item("receiving") {
            ListCard {
                ListRow("Ask before accepting", trailing = {
                    ToggleSwitch(state.requireConfirmation, viewModel::updateRequireConfirmation, "Ask before accepting")
                })
                RowDivider(12.dp)
                ListRow("Save to", trailing = {
                    Text("Downloads/DropFlow", style = DropFlowType.body, color = colors.label2)
                })
            }
        }
        item("receiving-foot") {
            SectionFooter(if (state.requireConfirmation) "Verified files keep their folders. Same names save as copies."
                else "Anyone who can reach this device can send without asking.",
                Modifier.padding(top = 6.dp), color = if (state.requireConfirmation) colors.label2 else colors.orange)
        }
        sectionGap("gap-sharing")
        item("sharing-head") { SectionHeader("Sharing") }
        item("sharing") {
            ListCard {
                ListRow("Direct link", subtitle = "Experimental", subtitleColor = colors.orange, trailing = {
                    ToggleSwitch(state.directLink && canLink, viewModel::updateDirectLink, "Direct link", enabled = canLink)
                })
            }
        }
        item("sharing-foot") {
            SectionFooter(if (canLink) "Moves a transfer onto a private Wi‑Fi link when the other device offers one. Android may ask before joining."
                else "This phone cannot keep its own Wi‑Fi while joining another network.", Modifier.padding(top = 6.dp))
        }
        if (BuildConfig.NEARBY_LAB) {
            sectionGap("gap-nearby")
            item("nearby-head") { SectionHeader("Nearby Link", badge = { Tag("Experimental", tone = Tone.Orange) }) }
            item("nearby") {
                ListCard {
                    ListRow("Open Lab", subtitle = "Direct link to Windows · synthetic data",
                        leading = { IconTile(Icons.Rounded.Science, tone = Tone.Orange) },
                        trailing = { Chevron() },
                        onClick = {
                            context.startActivity(Intent().setClassName(context, "${context.packageName}.nearby.NearbyLinkLabActivity"))
                        })
                }
            }
            item("nearby-foot") {
                SectionFooter("Choose the mode and turn the test on inside the lab.", Modifier.padding(top = 6.dp))
            }
        }
        sectionGap("gap-appearance")
        item("appearance-head") { SectionHeader("Appearance") }
        item("appearance") {
            ListCard {
                SegmentRow("Theme", null) {
                    Segmented(ThemeMode.entries.map { it to it.label }, state.themeMode, viewModel::updateThemeMode,
                        Modifier.width(204.dp), label = "Theme")
                }
                RowDivider(12.dp)
                SegmentRow("Glass", glassDescription(glassMode, state.glassStyle)) {
                    Segmented(GlassStyle.entries.map { it to it.label }, state.glassStyle, viewModel::updateGlassStyle,
                        Modifier.width(204.dp), label = "Glass")
                }
                RowDivider(12.dp)
                ListRow("Reduce motion", trailing = {
                    ToggleSwitch(state.reduceMotion, viewModel::updateReduceMotion, "Reduce motion")
                })
            }
        }
        item("appearance-foot") {
            SectionFooter("Android's animation settings are always respected.", Modifier.padding(top = 6.dp))
        }
        sectionGap("gap-more")
        item("more") {
            ListCard {
                ListRow("Updates", leading = { IconTile(Icons.Rounded.SystemUpdate, tone = Tone.Accent) },
                    trailing = { Chevron() }, onClick = onUpdates)
                RowDivider()
                ListRow("About DropFlow", leading = { IconTile(Icons.Rounded.Info, tone = Tone.Neutral) },
                    trailing = { Chevron() }, onClick = onAbout)
            }
        }
        item("foot") {
            SectionFooter("Keep DropFlow open while sharing. Both devices need the same Wi‑Fi.",
                Modifier.padding(top = 10.dp), color = colors.label3)
        }
    }
}

@Composable
private fun Chevron() {
    Icon(Icons.Rounded.ChevronRight, null, tint = Palette.label3, modifier = Modifier.size(20.dp))
}

@Composable
private fun SegmentRow(title: String, description: String?, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = DropFlowType.body, fontWeight = FontWeight.Medium, color = Palette.label)
            if (description != null) Text(description, style = DropFlowType.caption, color = Palette.label2)
        }
        control()
    }
}

private fun glassDescription(mode: GlassMode, style: GlassStyle): String = when (mode) {
    GlassMode.LENS -> "Refracting"
    GlassMode.BLUR -> if (style == GlassStyle.FROSTED) "Frosted" else "Frosted · lens needs Android 13"
    GlassMode.FROST -> "Tinted · blur needs Android 12"
    GlassMode.SOLID -> if (style == GlassStyle.SOLID) "Opaque" else "Opaque · high contrast"
}

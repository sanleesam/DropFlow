package com.dropflow.android.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dropflow.android.R
import com.dropflow.android.ui.components.IconTile
import com.dropflow.android.ui.components.ListCard
import com.dropflow.android.ui.components.ListRow
import com.dropflow.android.ui.components.Page
import com.dropflow.android.ui.components.RowDivider
import com.dropflow.android.ui.components.SectionFooter
import com.dropflow.android.ui.components.Tag
import com.dropflow.android.ui.components.Tone
import com.dropflow.android.ui.components.sectionGap
import com.dropflow.android.ui.navigation.Screen
import com.dropflow.android.ui.theme.DropFlowType
import com.dropflow.android.ui.theme.Palette

@Composable
fun AboutScreen(viewModel: AboutViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val colors = Palette
    Page(Screen.About.title, modifier) {
        item("identity") {
            ListCard {
                Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Image(painterResource(R.drawable.ic_dropflow_logo), "DropFlow", Modifier.size(84.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("DropFlow", style = DropFlowType.title, color = colors.label)
                    Tag("Android ${state.appVersion}", tone = Tone.Accent)
                    Text(state.buildChannel, style = DropFlowType.caption, color = colors.label3)
                }
            }
        }
        sectionGap("gap-features")
        item("features") {
            ListCard {
                ListRow("Your network, your files", subtitle = "Straight to Android, macOS and Windows on your Wi‑Fi. No accounts, no cloud.",
                    subtitleMaxLines = 2, leading = { IconTile(Icons.Rounded.Wifi, tone = Tone.Accent) })
                RowDivider()
                ListRow("Checked on arrival", subtitle = "Verified before it reaches Downloads/DropFlow. You choose what to accept.",
                    subtitleMaxLines = 2, leading = { IconTile(Icons.Rounded.VerifiedUser, tone = Tone.Green) })
            }
        }
        item("foot") {
            SectionFooter("Local-first sharing.", Modifier.padding(top = 10.dp), color = colors.label3)
        }
    }
}

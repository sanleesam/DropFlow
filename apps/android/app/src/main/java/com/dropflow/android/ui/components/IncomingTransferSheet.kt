package com.dropflow.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dropflow.android.core.network.transfer.TransferRequestPayload
import com.dropflow.android.core.util.formatBytes
import com.dropflow.android.ui.glass.GlassMode
import com.dropflow.android.ui.glass.LocalGlassMode
import com.dropflow.android.ui.theme.DropFlowType
import com.dropflow.android.ui.theme.Palette
import com.dropflow.android.ui.theme.Radii
import com.dropflow.android.ui.theme.Shapes

/**
 * Approval sheet. It lives in its own window, so instead of sampling the page
 * it is frosted and the page behind it blurs (see DropFlowNavHost).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingTransferSheet(
    request: TransferRequestPayload,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val colors = Palette
    val platformIcon: ImageVector = when (request.senderPlatform.lowercase()) {
        "macbook", "laptop" -> Icons.Rounded.Laptop
        "tablet" -> Icons.Rounded.TabletAndroid
        "phone", "mobile" -> Icons.Rounded.Smartphone
        else -> Icons.Rounded.Computer
    }
    val formattedSize = remember(request.totalSizeBytes) { formatBytes(request.totalSizeBytes) }
    val inset = 20.dp
    ModalBottomSheet(
        onDismissRequest = onDecline,
        sheetState = sheetState,
        shape = Shapes.Sheet,
        containerColor = if (LocalGlassMode.current == GlassMode.SOLID) colors.glassSolid else colors.glassFrost,
        contentColor = colors.label,
        scrimColor = colors.scrim,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.padding(top = 8.dp, bottom = 4.dp).width(36.dp).height(5.dp).background(colors.fillStrong, Shapes.Capsule))
        },
    ) {
        Column(Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = inset)
            .padding(top = 8.dp, bottom = 12.dp)
            .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DeviceAvatar(platformIcon, size = 50.dp)
                Column(Modifier.weight(1f)) {
                    Text(request.senderName, style = DropFlowType.title, color = colors.label,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Wants to send you", style = DropFlowType.footnote, color = colors.label2)
                }
            }
            Row(Modifier
                .fillMaxWidth()
                .background(colors.fill2, RoundedCornerShape(Radii.concentric(Radii.Sheet, inset - 4.dp)))
                .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(Icons.Rounded.Description, tone = Tone.Accent, size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(request.primaryFileName, style = DropFlowType.callout, fontWeight = FontWeight.SemiBold,
                        color = colors.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (request.totalFiles > 1) "${request.totalFiles} files · $formattedSize" else formattedSize,
                        style = DropFlowType.footnote, color = colors.label2)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(Icons.Rounded.Shield, null, tint = colors.label3, modifier = Modifier.size(15.dp))
                Text("Sender isn't verified. Accept only what you expect.", style = DropFlowType.footnote, color = colors.label2)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CapsuleButton("Decline", onDecline, Modifier.weight(1f), large = true)
                CapsuleButton("Accept", onAccept, Modifier.weight(1f), style = ButtonStyle.Accent, large = true)
            }
        }
    }
}

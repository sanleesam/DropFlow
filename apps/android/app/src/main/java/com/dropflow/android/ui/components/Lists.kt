package com.dropflow.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dropflow.android.core.model.DeviceType
import com.dropflow.android.ui.theme.DropFlowType
import com.dropflow.android.ui.theme.Motion
import com.dropflow.android.ui.theme.Palette
import com.dropflow.android.ui.theme.Radii
import com.dropflow.android.ui.theme.Shapes

/** Section title above a card, with an optional count or trailing link. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, count: Int? = null,
                  action: String? = null, onAction: (() -> Unit)? = null, badge: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 0.dp, bottom = 2.dp).heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = DropFlowType.headline, color = Palette.label, modifier = Modifier.semantics { heading() })
        if (count != null && count > 0) {
            Spacer(Modifier.size(8.dp))
            Text("$count", style = DropFlowType.subhead, color = Palette.label3)
        }
        if (badge != null) {
            Spacer(Modifier.size(8.dp))
            badge()
        }
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) CapsuleButton(action, onAction, style = ButtonStyle.Plain)
    }
}

@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier, color: Color = Palette.label2) {
    Text(text, style = DropFlowType.footnote, color = color, modifier = modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

/** Plain content surface. Rows inside use concentric corners. */
@Composable
fun ListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = Palette
    Column(modifier
        .fillMaxWidth()
        .background(colors.surface, Shapes.Card)
        .padding(Radii.Inset), content = content)
}

/** One row of a card that is split across lazy items, so long lists stay lazy. */
@Composable
fun CardSegment(first: Boolean, last: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val r = Radii.Card
    val shape = RoundedCornerShape(topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp)
    Column(modifier
        .fillMaxWidth()
        .background(Palette.surface, shape)
        .padding(start = Radii.Inset, end = Radii.Inset, top = if (first) Radii.Inset else 0.dp,
            bottom = if (last) Radii.Inset else 0.dp), content = content)
}

fun <T> LazyListScope.cardItems(items: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
        CardSegment(first = index == 0, last = index == items.lastIndex) {
            if (index > 0) RowDivider()
            content(item)
        }
    }
}

/** Hairline between rows, inset past the leading element. */
@Composable
fun RowDivider(inset: Dp = 58.dp) {
    Box(Modifier.fillMaxWidth().padding(start = inset, end = 12.dp).height(0.5.dp).background(Palette.separator))
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = Palette.label2,
    titleColor: Color = Palette.label,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    enabled: Boolean = true,
    subtitleLeading: (@Composable () -> Unit)? = null,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 1,
    detail: String? = null,
    detailColor: Color = Palette.red,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val highlight by animateColorAsState(if (pressed) Palette.fill2 else Color.Transparent, Motion.smooth(), label = "row")
    val clickModifier = when {
        selected != null && onClick != null -> Modifier.selectable(selected, interaction, null, enabled, Role.RadioButton, onClick)
        onClick != null -> Modifier.clickable(interaction, null, enabled, role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    Row(modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .background(highlight, Shapes.Row)
        .then(clickModifier)
        .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = DropFlowType.body, fontWeight = FontWeight.Medium, color = titleColor,
                maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                subtitleLeading?.invoke()
                Text(subtitle, style = DropFlowType.footnote, color = subtitleColor, maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis)
            }
            if (detail != null) Text(detail, style = DropFlowType.footnote, color = detailColor, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke(this)
    }
}

/** Rounded-square tinted icon tile for list rows. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tone: Tone = Tone.Accent, size: Dp = 36.dp) {
    val (bg, fg) = toneColors(tone)
    Box(modifier.size(size).background(bg, Shapes.Tile), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(size * 0.52f))
    }
}

fun deviceIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.DESKTOP -> Icons.Rounded.Computer
    DeviceType.LAPTOP -> Icons.Rounded.Laptop
    DeviceType.TABLET -> Icons.Rounded.TabletAndroid
    DeviceType.PHONE -> Icons.Rounded.Smartphone
}

/** Monogram-style device avatar. Decorative: adjacent text names the device. */
@Composable
fun DeviceAvatar(icon: ImageVector, modifier: Modifier = Modifier, local: Boolean = false,
                 selected: Boolean = false, size: Dp = 38.dp) {
    val colors = Palette
    Box(modifier.size(size)) {
        Box(Modifier.size(size).background(if (local) colors.accentFill else colors.fill, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (local) colors.accent else colors.label2, modifier = Modifier.size(size * 0.5f))
        }
        if (selected) Box(Modifier
            .align(Alignment.BottomEnd)
            .size(16.dp)
            .background(colors.surface, CircleShape)
            .padding(1.5.dp)
            .background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, tint = colors.onAccent, modifier = Modifier.size(10.dp))
        }
    }
}

/** Centered empty state; one title and one short line. */
@Composable
fun EmptyState(icon: ImageVector, title: String, modifier: Modifier = Modifier, message: String? = null,
               action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(44.dp).background(Palette.fill2, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Palette.label3, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(title, style = DropFlowType.headline, color = Palette.label)
        if (message != null) Text(message, style = DropFlowType.footnote, color = Palette.label2)
        if (action != null) {
            Spacer(Modifier.height(6.dp))
            action()
        }
    }
}

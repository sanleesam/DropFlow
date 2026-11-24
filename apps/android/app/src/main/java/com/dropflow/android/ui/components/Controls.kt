package com.dropflow.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dropflow.android.ui.theme.DropFlowType
import com.dropflow.android.ui.theme.Motion
import com.dropflow.android.ui.theme.Palette
import com.dropflow.android.ui.theme.Shapes
import com.dropflow.android.ui.theme.Springs
import kotlin.math.abs
import kotlin.math.min

/** Springy press feedback, shared by every tappable control. */
@Composable
fun rememberPressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): State<Float> {
    val isPressed by interaction.collectIsPressedAsState()
    return animateFloatAsState(if (isPressed) pressed else 1f, Motion.snappy(), label = "press")
}

enum class ButtonStyle { Accent, Fill, Plain, Destructive }

@Composable
fun CapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Fill,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    large: Boolean = false,
) {
    val colors = Palette
    val interaction = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interaction)
    val (container, content) = when (style) {
        ButtonStyle.Accent -> colors.accent to colors.onAccent
        ButtonStyle.Fill -> colors.fill to colors.label
        ButtonStyle.Plain -> Color.Transparent to colors.accent
        ButtonStyle.Destructive -> colors.redFill to colors.red
    }
    Row(modifier
        .minimumInteractiveComponentSize()
        .heightIn(min = if (large) 50.dp else 40.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
        .clip(Shapes.Capsule)
        .background(container)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = if (style == ButtonStyle.Plain) 8.dp else if (large) 22.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, tint = content, modifier = Modifier.size(if (large) 19.dp else 17.dp))
        Text(text, color = content, style = if (large) DropFlowType.headline else DropFlowType.callout,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Round icon button with a fill; use inside glass or on content. */
@Composable
fun IconCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = Palette.label,
    container: Color = Color.Transparent,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interaction, 0.9f)
    Box(modifier
        .size(size)
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
        .clip(CircleShape)
        .background(container)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(min(22f, size.value * 0.5f).dp))
    }
}

/** On/off control: accent track, white thumb that stretches while pressed. */
@Composable
fun ToggleSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String,
                 modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = Palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val track by animateColorAsState(if (checked) colors.accent else colors.fillStrong, Motion.smooth(), label = "track")
    val thumbWidth by animateDpAsState(if (pressed && enabled) 33.dp else 27.dp, Motion.snappy(), label = "thumbWidth")
    val travel = 51.dp - 4.dp - thumbWidth
    val position by animateFloatAsState(if (checked) 1f else 0f, Motion.snappy(), label = "thumb")
    Box(modifier
        .minimumInteractiveComponentSize()
        .alpha(if (enabled) 1f else 0.4f)
        .size(51.dp, 31.dp)
        .clip(Shapes.Capsule)
        .background(track)
        .toggleable(checked, interaction, indication = null, enabled = enabled, role = Role.Switch,
            onValueChange = onCheckedChange)
        .semantics { contentDescription = label }) {
        Box(Modifier
            .offset { IntOffset((2.dp + travel * position).roundToPx(), 2.dp.roundToPx()) }
            .size(thumbWidth, 27.dp)
            .shadow(3.dp, Shapes.Capsule, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .background(Color.White, Shapes.Capsule))
    }
}

/**
 * Segmented control whose thumb glides between options on the bouncy spring and
 * squashes briefly mid-flight like a drop of liquid.
 */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val colors = Palette
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val motion = Motion.enabled
    val glide = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index, motion) {
        if (motion) glide.animateTo(index.toFloat(), Springs.bouncy()) else glide.snapTo(index.toFloat())
    }
    val thumb = if (colors.isDark) Color.White.copy(alpha = 0.22f) else Color.White
    BoxWithConstraints(modifier
        .height(36.dp)
        .clip(Shapes.Capsule)
        .background(colors.fill2)
        .padding(2.dp)
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)) {
        val segment = maxWidth / options.size
        val speed = min(abs(glide.velocity) / 12f, 0.12f)
        Box(Modifier
            .offset { IntOffset((segment * glide.value).roundToPx(), 0) }
            .width(segment)
            .fillMaxHeight()
            .graphicsLayer { scaleX = 1f + speed; scaleY = 1f - speed * 0.8f }
            .shadow(2.dp, Shapes.Capsule, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
            .background(thumb, Shapes.Capsule))
        Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
            options.forEachIndexed { i, (value, text) ->
                val active = i == index
                Box(Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(Shapes.Capsule)
                    .selectable(active, role = Role.Tab, interactionSource = null, indication = null) { onSelect(value) },
                    contentAlignment = Alignment.Center) {
                    Text(text, style = DropFlowType.footnote, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) colors.label else colors.label2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

enum class Tone { Neutral, Accent, Green, Red, Orange }

@Composable
fun toneColors(tone: Tone): Pair<Color, Color> {
    val c = Palette
    return when (tone) {
        Tone.Neutral -> c.fill2 to c.label2
        Tone.Accent -> c.accentFill to c.accent
        Tone.Green -> c.greenFill to c.green
        Tone.Red -> c.redFill to c.red
        Tone.Orange -> c.orangeFill to c.orange
    }
}

/** Small status capsule. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral) {
    val (bg, fg) = toneColors(tone)
    Text(text, color = fg, style = DropFlowType.caption, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.clip(Shapes.Capsule).background(bg).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).background(color, CircleShape))
}

/** Thin capsule progress bar, eased with the smooth spring. */
@Composable
fun ProgressBar(value: Float, modifier: Modifier = Modifier, color: Color = Palette.accent) {
    val progress by animateFloatAsState(value.coerceIn(0f, 1f), Motion.smooth(), label = "progress")
    Box(modifier.fillMaxWidth().height(4.dp).clip(Shapes.Capsule).background(Palette.fill)) {
        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(color, Shapes.Capsule))
    }
}

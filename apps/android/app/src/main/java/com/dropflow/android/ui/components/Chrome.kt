package com.dropflow.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dropflow.android.ui.glass.Glass
import com.dropflow.android.ui.glass.GlassSize
import com.dropflow.android.ui.theme.DropFlowType
import com.dropflow.android.ui.theme.Motion
import com.dropflow.android.ui.theme.Palette
import com.dropflow.android.ui.theme.Springs
import kotlin.math.abs
import kotlin.math.min

/**
 * Shared between the floating chrome and the page underneath it: how much room
 * the chrome takes, and whether the page has scrolled under the top edge.
 */
@Stable
class ChromeState {
    var top: Dp by mutableStateOf(0.dp)
    var bottom: Dp by mutableStateOf(0.dp)
    var start: Dp by mutableStateOf(0.dp)
    var scrolled: Boolean by mutableStateOf(false)
}

val LocalChrome = compositionLocalOf { ChromeState() }

val TopBarHeight = 56.dp

/**
 * A page owns its scroll view. The large title scrolls away and reappears as
 * an inline title in the floating toolbar once content passes beneath it.
 */
@Composable
fun Page(title: String, modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(),
         content: LazyListScope.() -> Unit) {
    val chrome = LocalChrome.current
    LaunchedEffect(state, chrome) {
        snapshotFlow { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 48 }
            .collect { chrome.scrolled = it }
    }
    LazyColumn(modifier.fillMaxSize().imePadding(), state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = chrome.top, bottom = chrome.bottom + 28.dp)) {
        item("page-title") {
            Text(title, style = DropFlowType.largeTitle, color = Palette.label,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 18.dp).semantics { heading() })
        }
        content()
    }
}

/** Vertical rhythm between page sections. */
fun LazyListScope.sectionGap(key: Any, height: Dp = 26.dp) {
    item(key) { Spacer(Modifier.height(height)) }
}

/**
 * Floating toolbar: a soft scroll-edge fade under the status bar, glass buttons
 * on either side and the inline title that appears on scroll.
 */
@Composable
fun TopChrome(title: String, scrolled: Boolean, modifier: Modifier = Modifier,
              navigation: (@Composable () -> Unit)? = null,
              actions: (@Composable RowScope.() -> Unit)? = null) {
    val colors = Palette
    val titleAlpha by animateFloatAsState(if (scrolled) 1f else 0f, Motion.smooth(), label = "inlineTitle")
    val edge = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 18.dp
    Box(modifier.fillMaxWidth()) {
        Box(Modifier
            .fillMaxWidth()
            .height(edge)
            .alpha(0.6f + 0.4f * titleAlpha)
            .background(Brush.verticalGradient(0f to colors.window, 0.55f to colors.window.copy(alpha = 0.97f),
                0.8f to colors.window.copy(alpha = 0.7f), 1f to colors.window.copy(alpha = 0f))))
        Row(Modifier.statusBarsPadding().fillMaxWidth().height(TopBarHeight).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterStart) { navigation?.invoke() }
            Text(title, style = DropFlowType.headline, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alpha(titleAlpha), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.End) {
                if (actions != null) Glass(size = GlassSize.Small, elevation = 6.dp) {
                    Row(Modifier.padding(2.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
                }
            }
        }
    }
}

data class TabItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** Gliding selection index that squashes with speed, like a drop of liquid. */
@Composable
private fun rememberGlide(index: Int): Pair<Float, Float> {
    val motion = Motion.enabled
    val glide = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index, motion) {
        if (motion) glide.animateTo(index.toFloat(), Springs.bouncy()) else glide.snapTo(index.toFloat())
    }
    return glide.value to min(abs(glide.velocity) / 14f, 0.14f)
}

/** Floating glass tab bar with a lens-like thumb that glides between tabs. */
@Composable
fun GlassTabBar(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Palette
    Glass(modifier.fillMaxWidth().height(64.dp), size = GlassSize.Medium, cornerRadius = 32.dp) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
            val width = maxWidth / items.size
            val (position, squash) = rememberGlide(selected)
            if (selected >= 0) Box(Modifier
                .offset(x = width * position)
                .width(width)
                .fillMaxHeight()
                .graphicsLayer { scaleX = 1f + squash; scaleY = 1f - squash * 0.7f }
                .background(colors.fill, androidx.compose.foundation.shape.RoundedCornerShape(28.dp)))
            Row(Modifier.fillMaxSize().selectableGroup()) {
                items.forEachIndexed { index, item ->
                    TabButton(item, index == selected, { onSelect(index) }, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/** Vertical variant for wide windows. */
@Composable
fun GlassRail(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Palette
    val itemHeight = 64.dp
    Glass(modifier.width(76.dp).height(itemHeight * items.size + 8.dp), size = GlassSize.Medium, cornerRadius = 32.dp) {
        Box(Modifier.fillMaxSize().padding(4.dp)) {
            val (position, squash) = rememberGlide(selected)
            if (selected >= 0) Box(Modifier
                .offset(y = itemHeight * position)
                .fillMaxWidth()
                .height(itemHeight)
                .graphicsLayer { scaleY = 1f + squash; scaleX = 1f - squash * 0.7f }
                .background(colors.fill, androidx.compose.foundation.shape.RoundedCornerShape(28.dp)))
            Column(Modifier.fillMaxSize().selectableGroup()) {
                items.forEachIndexed { index, item ->
                    TabButton(item, index == selected, { onSelect(index) }, Modifier.fillMaxWidth().height(itemHeight))
                }
            }
        }
    }
}

@Composable
private fun TabButton(item: TabItem, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = Palette
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val scale by rememberPressScale(interaction, 0.9f)
    Column(modifier
        .selectable(active, interaction, null, role = Role.Tab, onClick = onClick)
        .graphicsLayer { scaleX = scale; scaleY = scale },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(if (active) item.selectedIcon else item.icon, null, tint = if (active) colors.accent else colors.label,
            modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(3.dp))
        Text(item.label, style = DropFlowType.tab, color = if (active) colors.accent else colors.label, maxLines = 1)
    }
}

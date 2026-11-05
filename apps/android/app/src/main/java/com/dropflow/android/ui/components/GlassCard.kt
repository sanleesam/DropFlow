package com.dropflow.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dropflow.android.ui.theme.SurfaceGlass
import com.dropflow.android.ui.theme.SurfaceGlassBorder
import com.dropflow.android.ui.theme.IcyBlue

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    borderColor: Color = SurfaceGlassBorder,
    backgroundColor: Color = SurfaceGlass,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier,
        shape = shape,
        color = backgroundColor,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(
            if (borderColor == SurfaceGlassBorder) IcyBlue.copy(alpha = 0.30f) else borderColor,
            borderColor.copy(alpha = 0.45f),
            Color.White.copy(alpha = 0.035f),
            borderColor.copy(alpha = 0.70f)
        )))
    ) {
        Box(modifier = Modifier.background(Brush.linearGradient(listOf(
            Color(0x0DD9EAFF), Color.Transparent, Color.Transparent
        ))), content = content)
    }
}

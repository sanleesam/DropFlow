package com.dropflow.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Apple-style text styles mapped onto the platform sans. */
object DropFlowType {
    private val base = TextStyle(fontFamily = FontFamily.Default)
    val largeTitle = base.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val title = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val headline = base.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val body = base.copy(fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.2).sp)
    val callout = base.copy(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp)
    val subhead = base.copy(fontSize = 14.sp, lineHeight = 19.sp)
    val footnote = base.copy(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = base.copy(fontSize = 12.sp, lineHeight = 16.sp)
    val tab = base.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
    val sectionHeader = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
}

val Typography = Typography(
    headlineSmall = DropFlowType.title,
    titleLarge = DropFlowType.title,
    titleMedium = DropFlowType.headline,
    bodyLarge = DropFlowType.body,
    bodyMedium = DropFlowType.subhead,
    bodySmall = DropFlowType.footnote,
    labelLarge = DropFlowType.callout.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = DropFlowType.caption,
    labelSmall = DropFlowType.tab,
)

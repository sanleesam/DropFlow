package com.dropflow.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.ui.theme.*

@Composable
fun TitaniumBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(BackgroundDark).background(Brush.verticalGradient(listOf(
        Color(0x123B82F6), Color.Transparent, Color.Transparent
    ))), content = content)
}

@Composable
fun PageIntro(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(eyebrow.uppercase(), color = IcyBlue, fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Text(title, color = TextPrimary, fontSize = 30.sp, lineHeight = 36.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
        Text(subtitle, color = TextSecondary, fontSize = 14.sp, lineHeight = 21.sp)
    }
}

@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = IcyBlue) {
    Box(modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
        .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.13f), SurfaceInset)))
        .border(1.dp, tint.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun StatusPill(text: String, color: Color = IcyBlue) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.09f))
            .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp))
}

@Composable
fun PrimaryAction(text: String, icon: ImageVector, onClick: () -> Unit,
                  modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 50.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BlueAccentStrong,
            contentColor = TextPrimary, disabledContainerColor = SurfaceInset,
            disabledContentColor = TextMuted),
        border = androidx.compose.foundation.BorderStroke(1.dp,
            if (enabled) IcyBlue.copy(alpha = 0.45f) else SurfaceGlassBorder)) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

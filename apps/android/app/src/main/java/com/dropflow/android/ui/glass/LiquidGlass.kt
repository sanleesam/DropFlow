package com.dropflow.android.ui.glass

import android.graphics.ColorMatrixColorFilter
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dropflow.android.ui.theme.LocalDropFlowColors
import com.dropflow.android.ui.theme.Shapes
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The content layer that glass refracts. Everything drawn inside
 * [GlassBackdropHost] is recorded once per frame into a GPU layer; each [Glass]
 * on top re-draws the slice beneath it through blur, saturation and (on
 * Android 13+) the lens shader. Glass never samples other glass.
 */
@Stable
class GlassBackdrop {
    internal var layer: GraphicsLayer? by mutableStateOf(null)
    internal var origin: Offset by mutableStateOf(Offset.Zero)
}

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

@Composable
fun GlassBackdropHost(backdrop: GlassBackdrop, background: Color, modifier: Modifier = Modifier,
                      content: @Composable BoxScope.() -> Unit) {
    val mode = LocalGlassMode.current
    val sampling = mode.samplesBackdrop && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val layer = rememberGraphicsLayer()
    SideEffect { backdrop.layer = if (sampling) layer else null }
    Box(modifier
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            if (sampling) {
                layer.record {
                    drawRect(background)
                    this@drawWithContent.drawContent()
                }
                drawLayer(layer)
            } else {
                drawRect(background)
                drawContent()
            }
        }, content = content)
}

/** Desktop SIZE_TUNING: rim width as a share of the short side, lens strength, blur and saturation. */
enum class GlassSize(val bezel: Float, val strength: Dp, val blur: Dp, val frostBlur: Dp, val saturation: Float) {
    Small(0.42f, 12.dp, 2.dp, 14.dp, 1.7f),
    Medium(0.36f, 18.dp, 3.5.dp, 18.dp, 1.6f),
    Large(0.12f, 28.dp, 6.dp, 26.dp, 1.5f),
}

/**
 * A piece of the navigation/control layer. Use only for bars, docks, floating
 * buttons, menus and toasts — never for content, and never glass inside glass.
 */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    shape: Shape = Shapes.Capsule,
    size: GlassSize = GlassSize.Medium,
    cornerRadius: Dp = 999.dp,
    elevation: Dp = 10.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalDropFlowColors.current
    val mode = LocalGlassMode.current
    val backdrop = LocalGlassBackdrop.current
    var position by remember { mutableStateOf(Offset.Zero) }
    val shadowAlpha = if (colors.isDark) 0.55f else 0.2f
    Box(modifier
        .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false,
            ambientColor = colors.shadow.copy(alpha = shadowAlpha), spotColor = colors.shadow.copy(alpha = shadowAlpha))
        else Modifier)
        .onGloballyPositioned { position = it.positionInRoot() }
        .drawWithCache {
            val outline = shape.createOutline(this.size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val source = backdrop?.layer
            val sampling = mode.samplesBackdrop && source != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val blurPx = (if (mode == GlassMode.LENS) size.blur else size.frostBlur).toPx()
            val strengthPx = size.strength.toPx()
            val pad = ceil(blurPx * 2.5f + strengthPx).toInt()
            val effectLayer = if (sampling) obtainGraphicsLayer() else null
            if (effectLayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val w = this.size.width
                val h = this.size.height
                val radiusPx = min(cornerRadius.toPx(), min(w, h) / 2f)
                val rim = min(min(min(w, h) * size.bezel, 48.dp.toPx()), maxOf(radiusPx, 10.dp.toPx()))
                effectLayer.renderEffect = glassEffect(blurPx, size.saturation,
                    if (mode == GlassMode.LENS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        LensSpec(w, h, radiusPx, rim, strengthPx, pad.toFloat()) else null)
                effectLayer.topLeft = IntOffset(-pad, -pad)
            }
            val fill = when (mode) {
                GlassMode.SOLID -> colors.glassSolid
                GlassMode.FROST -> colors.glassFrost
                GlassMode.LENS -> if (sampling) colors.glassTintLens else colors.glassFrost
                GlassMode.BLUR -> if (sampling) colors.glassTint else colors.glassFrost
            }
            val angle = Math.toRadians(150.0)
            val dx = (sin(angle) * this.size.width / 2).toFloat()
            val dy = (-cos(angle) * this.size.height / 2).toFloat()
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val rimBrush = Brush.linearGradient(
                0f to colors.rimHi, 0.14f to colors.rimMid, 0.34f to colors.rimLo, 0.52f to Color.Transparent,
                0.72f to colors.rimLo, 1f to colors.rimMid,
                start = center - Offset(dx, dy), end = center + Offset(dx, dy))
            val sheen = Brush.verticalGradient(
                0f to Color.White.copy(alpha = colors.sheen), 0.5f to Color.Transparent,
                startY = 0f, endY = this.size.height)
            val hairline = 1.dp.toPx()
            onDrawBehind {
                if (effectLayer != null && source != null) {
                    val offset = backdrop.origin - position
                    effectLayer.record(size = IntSize(this.size.width.toInt() + pad * 2, this.size.height.toInt() + pad * 2)) {
                        translate(offset.x + pad, offset.y + pad) { drawLayer(source) }
                    }
                    clipPath(path) { drawLayer(effectLayer) }
                }
                drawPath(path, fill)
                if (mode == GlassMode.SOLID) {
                    drawPath(path, colors.separator, style = Stroke(hairline))
                } else {
                    drawPath(path, sheen)
                    drawPath(path, rimBrush, style = Stroke(hairline))
                }
            }
        }, contentAlignment = contentAlignment, content = content)
}

private class LensSpec(val width: Float, val height: Float, val radius: Float, val bezel: Float,
                       val strength: Float, val origin: Float)

@RequiresApi(Build.VERSION_CODES.S)
private fun glassEffect(blurPx: Float, saturation: Float, lens: LensSpec?): androidx.compose.ui.graphics.RenderEffect {
    var effect = if (blurPx > 0.5f) android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
    else null
    if (lens != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shaderEffect = lensEffect(lens)
        effect = if (effect == null) shaderEffect else android.graphics.RenderEffect.createChainEffect(shaderEffect, effect)
    }
    val matrix = ColorMatrix().apply { setToSaturation(saturation) }
    val filter = ColorMatrixColorFilter(matrix.values)
    effect = if (effect == null) android.graphics.RenderEffect.createColorFilterEffect(filter)
    else android.graphics.RenderEffect.createColorFilterEffect(filter, effect)
    return effect.asComposeRenderEffect()
}

private val lensPeak: Float by lazy { LensProfile.peak() }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun lensEffect(lens: LensSpec): android.graphics.RenderEffect {
    val shader = RuntimeShader(LENS_SHADER).apply {
        setFloatUniform("origin", lens.origin, lens.origin)
        setFloatUniform("size", lens.width, lens.height)
        setFloatUniform("radius", lens.radius)
        setFloatUniform("bezel", lens.bezel)
        setFloatUniform("strength", lens.strength)
        setFloatUniform("ior", LensProfile.GLASS_IOR)
        setFloatUniform("peak", lensPeak)
    }
    return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
}

/** Same squircle rim and Snell refraction as desktop `displacement.ts`, per pixel. */
private const val LENS_SHADER = """
uniform shader content;
uniform float2 origin;
uniform float2 size;
uniform float radius;
uniform float bezel;
uniform float strength;
uniform float ior;
uniform float peak;

float rimHeight(float t) {
    t = clamp(t, 0.0, 1.0);
    return pow(1.0 - pow(1.0 - t, 4.0), 0.25);
}

float refraction(float t) {
    float dt = 1.0 / 128.0;
    float a = max(0.0, t - dt);
    float b = min(1.0, t + dt);
    float slope = (rimHeight(b) - rimHeight(a)) / (b - a);
    float incidence = atan(slope);
    float refracted = asin(sin(incidence) / ior);
    return rimHeight(t) * tan(incidence - refracted) / peak;
}

half4 main(float2 coord) {
    float2 h = size * 0.5;
    float2 p = coord - origin - h;
    float r = min(radius, min(h.x, h.y));
    float2 q = abs(p) - (h - r);
    float2 o = max(q, float2(0.0));
    float outside = length(o);
    float sdf = outside + min(max(q.x, q.y), 0.0) - r;
    float rim = max(1.0, min(bezel, min(h.x, h.y)));
    float depth = -sdf;
    if (sdf > 0.0 || depth >= rim) {
        return content.eval(coord);
    }
    float2 n;
    if (outside > 0.0) {
        n = (o / outside) * sign(p);
    } else if (q.x > q.y) {
        n = float2(sign(p.x), 0.0);
    } else {
        n = float2(0.0, sign(p.y));
    }
    float m = refraction(depth / rim);
    return content.eval(coord - n * m * strength);
}
"""

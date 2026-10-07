package com.spendwise.app.ui.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangle
import com.spendwise.app.ui.theme.Radius
import com.spendwise.app.ui.theme.SpendWise
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Glass system (ADR-010: guilloché pattern so glass is visible; builds on ADR-009's calm palette).
 *
 *  - A fine monochrome guilloché line field (banknote-style security print) sits behind everything. Glass
 *    refracts those lines at its curved edges, so it reads as glass even on empty screens — without hue.
 *  - Floating controls refract background + content; content cards refract the background only.
 *
 * Earlier notes (ADR-009):
 *
 *  - Glass lives ONLY on the floating layer — tab bar, "+" button, top bar — where it refracts the real content
 *    scrolling underneath. That is when glass actually reads as glass (iOS does the same).
 *  - Content (cards, lists, money) sits on calm SOLID surfaces with a glass-like edge: specular top rim,
 *    defining hairline and, in light, a soft shadow. Readable first.
 *  - Glass is always NEUTRAL: no hue tints, no saturation boost. Colour only comes from what is behind it.
 *  - The background is neutral: one soft light at the top, no colour pools.
 *  - Light theme glass is visible through tone + edge + shadow, not whiteness.
 *  - Fallback (battery saver, thermal, reduced motion, "Reduce transparency"): same shapes and edges,
 *    no blur/lens, near-opaque tint.
 */

/** Background + screen content. Sampled by floating controls drawn above the content. */
val LocalContentBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Background (tone + guilloché) only. Sampled by glass that lives inside scrolling content. */
val LocalBackgroundBackdrop = staticCompositionLocalOf<Backdrop?> { null }

enum class GlassKind {
    /** Floating controls (tab bar, "+", pills): clear, strong lens, refract background + content. */
    Control,
    /** Content cards: more tint so text stays >= 4.5:1; refract the background pattern. */
    Card,
}

/** Whether refraction/blur is allowed right now. */
val LocalBlurAllowed = staticCompositionLocalOf { true }

/** The scaffold-level top bar, so it can refract the whole screen (screens publish their state into it). */
val LocalGlassTopBar = staticCompositionLocalOf<GlassTopBarState?> { null }

@Stable
class GlassTopBarState {
    var title by mutableStateOf<String?>(null)
    /** Content has scrolled under the status bar → show the blurred scroll edge. */
    var scrolled by mutableStateOf(false)
    /** The large title has scrolled away → show the inline title. */
    var titleVisible by mutableStateOf(false)
    internal var owner: Any? = null
}

@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    reduceTransparency: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val contentBackdrop = rememberLayerBackdrop()
    val backgroundBackdrop = rememberLayerBackdrop()
    val topBar = remember { GlassTopBarState() }
    val blurAllowed = rememberBlurAllowed(reduceTransparency)

    CompositionLocalProvider(
        LocalContentBackdrop provides contentBackdrop,
        LocalBackgroundBackdrop provides backgroundBackdrop,
        LocalBlurAllowed provides blurAllowed,
        LocalGlassTopBar provides topBar,
    ) {
        Box(modifier.fillMaxSize().background(SpendWise.colors.background)) {
            Box(Modifier.fillMaxSize().layerBackdrop(contentBackdrop)) {
                NeutralBackground(Modifier.fillMaxSize().layerBackdrop(backgroundBackdrop))
                content()
            }
            overlay()
        }
    }
}

/**
 * Neutral background: flat tone, one soft light from the top, and a monochrome guilloché line field —
 * concentric rings with a sine "rosette" modulation, like banknote security printing. Static and cached:
 * the path is built once per size.
 */
@Composable
fun NeutralBackground(modifier: Modifier = Modifier) {
    val c = SpendWise.colors
    // The guilloché is ~60k path segments: rasterise it ONCE per size into an alpha mask, then every frame just
    // draws that bitmap tinted with the theme colour (cheap, and theme crossfades never re-rasterise).
    val mask = remember { PatternMaskCache() }
    Box(
        modifier.drawBehind {
            val light = Brush.radialGradient(
                colors = listOf(c.backgroundLight, c.backgroundLight.copy(alpha = 0f)),
                center = Offset(size.width * 0.3f, -size.height * 0.05f),
                radius = size.maxDimension * 0.75f,
            )
            drawRect(c.background)
            drawRect(light)
            // Lines = a lavender→neutral linear gradient masked by the cached guilloché bitmap, composed on the
            // GPU in one shader (no per-frame rasterising).
            val paint = mask.paint(this, c.patternAccent, c.pattern)
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
        },
    )
}

/** Holds the rasterised guilloché mask; rebuilt only when the drawing size changes. */
private class PatternMaskCache {
    private var bitmap: ImageBitmap? = null
    private var cachedSize = Size.Zero
    private var cachedPaint: android.graphics.Paint? = null
    private var paintKey: Triple<Size, Color, Color>? = null

    /** Gradient (top-right [accent] → bottom-left [neutral]) masked by the pattern bitmap. Rebuilt only when inputs change. */
    fun paint(scope: DrawScope, accent: Color, neutral: Color): android.graphics.Paint {
        val key = Triple(scope.size, accent, neutral)
        cachedPaint?.let { if (key == paintKey) return it }
        val maskBitmap = get(scope).asAndroidBitmap()
        val gradient = android.graphics.LinearGradient(
            scope.size.width, 0f, 0f, scope.size.height,
            accent.toArgb(), neutral.toArgb(), android.graphics.Shader.TileMode.CLAMP,
        )
        val maskShader = android.graphics.BitmapShader(
            maskBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP,
        )
        // DST_IN: keep the gradient only where the pattern mask has alpha.
        val shader = android.graphics.ComposeShader(gradient, maskShader, android.graphics.PorterDuff.Mode.DST_IN)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        cachedPaint = p
        paintKey = key
        return p
    }

    fun get(scope: DrawScope): ImageBitmap {
        val size = scope.size
        bitmap?.let { if (size == cachedSize) return it }
        val w = size.width.toInt().coerceAtLeast(1)
        val h = size.height.toInt().coerceAtLeast(1)
        val image = ImageBitmap(w, h, ImageBitmapConfig.Alpha8)
        val path = with(scope) { guillochePath(size.width, size.height, spacing = 12.dp.toPx(), amplitude = 3.dp.toPx()) }
        val strokePx = with(scope) { 1.dp.toPx() }
        CanvasDrawScope().draw(scope, scope.layoutDirection, Canvas(image), size) {
            drawPath(path, Color.White, style = Stroke(width = strokePx))
        }
        bitmap = image
        cachedSize = size
        return image
    }
}

/** Concentric rings centred off-screen top-right; radius wobbles with 28 petals, phase drifting per ring. */
private fun guillochePath(width: Float, height: Float, spacing: Float, amplitude: Float): Path {
    val path = Path()
    val cx = width * 1.05f
    val cy = height * 0.12f
    val maxR = hypot(cx, height - cy) + amplitude
    val petals = 28
    // Near the centre the rings are short, so a fixed petal count would zig-zag; fade the wave in with radius.
    val calmRadius = width * 0.45f
    var r = spacing * 2
    var ring = 0
    while (r < maxR) {
        val steps = (2 * PI * r / 5f).toInt().coerceIn(120, 900)
        val phase = ring * 0.32f
        val amp = amplitude * (r / calmRadius).coerceAtMost(1f).let { it * it }
        for (i in 0..steps) {
            val t = (i.toFloat() / steps) * 2f * PI.toFloat()
            val rr = r + amp * sin(petals * t + phase)
            val x = cx + rr * cos(t)
            val y = cy + rr * sin(t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        r += spacing
        ring++
    }
    return path
}

/**
 * Liquid glass. Control kind for floating controls; Card kind for content cards. Neutral tint, light blur, clear lens at the rim, specular highlight,
 * hairline edge and shadow. [layerBlock] transforms the glass itself (e.g. press scale) without distorting
 * what it samples.
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    kind: GlassKind = GlassKind.Control,
    tint: Color? = null,
    /** Override which backdrop is refracted (e.g. a control-style button that lives inside content). */
    backdropOverride: Backdrop? = null,
    lensHeight: Float = if (kind == GlassKind.Control) 18f else 12f,
    lensAmount: Float = if (kind == GlassKind.Control) 26f else 14f,
    blurDp: Float = if (kind == GlassKind.Control) 1.5f else 2f,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    val colors = SpendWise.colors
    val backdrop = backdropOverride
        ?: (if (kind == GlassKind.Control) LocalContentBackdrop.current else LocalBackgroundBackdrop.current)
        ?: return glassFallback(shape)
    val allowed = LocalBlurAllowed.current
    val fill = tint ?: when {
        !allowed -> if (kind == GlassKind.Card) colors.surface1 else colors.glassTintFallback
        kind == GlassKind.Card -> colors.glassCard
        else -> colors.glassTint
    }
    val dark = colors.isDark
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                if (allowed) {
                    // Light cards must NOT brighten what's behind them, or they wash out to plain white.
                    when {
                        dark -> colorControls(brightness = -0.04f, saturation = 1.05f)
                        kind == GlassKind.Control -> colorControls(brightness = 0f, saturation = 1.0f)
                        else -> colorControls(brightness = -0.01f, saturation = 1.0f)
                    }
                    blur(blurDp.dp.toPx())
                    // depthEffect thickens the refraction toward the rim, so the edge visibly bends light.
                    lens(lensHeight.dp.toPx(), lensAmount.dp.toPx(), depthEffect = kind == GlassKind.Control)
                }
            },
            // Directional specular rim: light from the top-left catches the curved edge, strongest where the
            // rim faces the light and fading round the far side — the iOS-style edge glint.
            highlight = {
                Highlight(
                    width = when {
                        kind == GlassKind.Card -> 1.5.dp
                        dark -> 2.dp
                        else -> 1.25.dp // light: a thinner rim, so the bar never reads as a white frame
                    },
                    blurRadius = 1.dp,
                    alpha = 1f,
                    style = HighlightStyle.Default(
                        color = Color.White.copy(alpha = if (dark) 0.75f else 0.85f),
                        angle = 45f,
                        falloff = 0.7f,
                    ),
                )
            },
            // Inner glow: light refracted just inside the rim gives the glass visible thickness.
            innerShadow = {
                InnerShadow(
                    radius = if (kind == GlassKind.Control) 10.dp else 8.dp,
                    offset = DpOffset(0.dp, 0.dp),
                    color = when {
                        dark -> Color.White.copy(alpha = 0.10f)
                        kind == GlassKind.Control -> Color.White.copy(alpha = 0.22f)
                        else -> Color.White.copy(alpha = 0.42f)
                    },
                )
            },
            shadow = {
                if (dark) Shadow(radius = 20.dp, offset = DpOffset(0.dp, 6.dp), color = Color.Black.copy(alpha = 0.5f))
                else Shadow(radius = 18.dp, offset = DpOffset(0.dp, 6.dp), color = colors.shadow)
            },
            layerBlock = layerBlock,
            onDrawSurface = { drawRect(fill) },
        )
        .border(1.dp, colors.glassHairline, shape)
}

/** Solid content surface with a glass-like edge: specular top rim → defining hairline, soft shadow in light. */
@Composable
fun Modifier.surfaceCard(shape: Shape = RoundedRectangle(Radius.card)): Modifier {
    val c = SpendWise.colors
    return this
        .then(if (c.isDark) Modifier else Modifier.shadow(10.dp, shape, clip = false, ambientColor = c.shadow, spotColor = c.shadow))
        .clip(shape)
        .background(c.surface1)
        .border(1.dp, Brush.verticalGradient(listOf(c.glassHighlightTop, c.glassHairline)), shape)
}

@Composable
private fun Modifier.glassFallback(shape: Shape): Modifier {
    val colors = SpendWise.colors
    return clip(shape)
        .background(colors.glassTintFallback)
        .border(1.dp, Brush.verticalGradient(listOf(colors.glassHighlightTop, colors.glassHairline)), shape)
}

/**
 * Refraction is disabled under battery saver, when thermal status ≥ MODERATE, when the system animator scale
 * is 0, or when the user turned on "Reduce transparency". Battery saver and thermal changes are observed live.
 */
@Composable
private fun rememberBlurAllowed(reduceTransparency: Boolean): Boolean {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var powerSave by remember { mutableStateOf(power.isPowerSaveMode) }
    var hot by remember { mutableStateOf(power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) }

    DisposableEffect(power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                powerSave = power.isPowerSaveMode
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val thermal = PowerManager.OnThermalStatusChangedListener { status ->
            hot = status >= PowerManager.THERMAL_STATUS_MODERATE
        }
        power.addThermalStatusListener(ContextCompat.getMainExecutor(context), thermal)
        onDispose {
            context.unregisterReceiver(receiver)
            power.removeThermalStatusListener(thermal)
        }
    }
    val reducedMotion = SpendWise.motion.reduced
    return !reduceTransparency && !powerSave && !hot && !reducedMotion
}

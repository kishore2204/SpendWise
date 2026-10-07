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
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangle
import com.spendwise.app.ui.theme.Radius
import com.spendwise.app.ui.theme.SpendWise

/*
 * Glass system (ADR-009, revises ADR-008).
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
    val topBar = remember { GlassTopBarState() }
    val blurAllowed = rememberBlurAllowed(reduceTransparency)

    CompositionLocalProvider(
        LocalContentBackdrop provides contentBackdrop,
        LocalBlurAllowed provides blurAllowed,
        LocalGlassTopBar provides topBar,
    ) {
        Box(modifier.fillMaxSize().background(SpendWise.colors.background)) {
            Box(Modifier.fillMaxSize().layerBackdrop(contentBackdrop)) {
                NeutralBackground(Modifier.fillMaxSize())
                content()
            }
            overlay()
        }
    }
}

/** Neutral background: flat tone with one soft light from the top. No hue. Static. */
@Composable
fun NeutralBackground(modifier: Modifier = Modifier) {
    val c = SpendWise.colors
    Box(
        modifier
            .background(c.background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(c.backgroundLight, c.backgroundLight.copy(alpha = 0f)),
                        center = Offset(size.width * 0.3f, -size.height * 0.05f),
                        radius = size.maxDimension * 0.75f,
                    ),
                )
            },
    )
}

/**
 * Liquid glass for FLOATING CONTROLS only. Neutral tint, light blur, clear lens at the rim, specular highlight,
 * hairline edge and shadow. [layerBlock] transforms the glass itself (e.g. press scale) without distorting
 * what it samples.
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color? = null,
    lensHeight: Float = 24f,
    lensAmount: Float = 40f,
    blurDp: Float = 6f,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    val colors = SpendWise.colors
    val backdrop = LocalContentBackdrop.current ?: return glassFallback(shape)
    val allowed = LocalBlurAllowed.current
    val fill = tint ?: if (allowed) colors.glassTint else colors.glassTintFallback
    val dark = colors.isDark
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                if (allowed) {
                    if (dark) colorControls(brightness = -0.04f, saturation = 1.05f)
                    else colorControls(brightness = 0.06f, saturation = 1.05f)
                    blur(blurDp.dp.toPx())
                    lens(lensHeight.dp.toPx(), lensAmount.dp.toPx(), depthEffect = true)
                }
            },
            highlight = { Highlight(width = 1.dp, alpha = if (dark) 0.8f else 1f) },
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

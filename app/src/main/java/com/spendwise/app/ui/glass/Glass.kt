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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangle
import com.spendwise.app.ui.theme.Radius
import com.spendwise.app.ui.theme.SpendWise

/*
 * Liquid glass design system (ADR-008, supersedes ADR-007's glass placement).
 *
 *  - Real refraction: content bends at the curved edges of glass (lens), lightly blurred, with vibrancy,
 *    a specular rim highlight and a soft shadow — not frosted blur.
 *  - Glass needs light behind it: a static four-colour aurora sits under everything.
 *  - Two backdrops:
 *      background backdrop = aurora only → used by glass *inside* scrolling content (cards), so a card never
 *                            samples itself;
 *      content backdrop    = aurora + screen content → used by floating controls (tab bar, + button, top bar).
 *  - Readability safeguard: cards carry a stronger tint than controls; money text stays text1 on that tint.
 *  - Automatic fallback (battery saver, thermal, reduced motion, "Reduce transparency"): same shapes and
 *    highlights, no blur/lens, near-opaque tint.
 */

/** Aurora + screen content. For floating controls drawn above the content. */
val LocalContentBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Aurora only. For glass that lives inside scrolling content. */
val LocalBackgroundBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Whether refraction/blur is allowed right now. */
val LocalBlurAllowed = staticCompositionLocalOf { true }

/** The scaffold-level top bar, so it can refract the whole screen (screens publish their title into it). */
val LocalGlassTopBar = staticCompositionLocalOf<GlassTopBarState?> { null }

@Stable
class GlassTopBarState {
    var title by mutableStateOf<String?>(null)
    var visible by mutableStateOf(false)
    internal var owner: Any? = null
}

@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    reduceTransparency: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val backgroundBackdrop = rememberLayerBackdrop()
    val contentBackdrop = rememberLayerBackdrop()
    val topBar = remember { GlassTopBarState() }
    val blurAllowed = rememberBlurAllowed(reduceTransparency)

    CompositionLocalProvider(
        LocalBackgroundBackdrop provides backgroundBackdrop,
        LocalContentBackdrop provides contentBackdrop,
        LocalBlurAllowed provides blurAllowed,
        LocalGlassTopBar provides topBar,
    ) {
        Box(modifier.fillMaxSize().background(SpendWise.colors.background)) {
            Box(Modifier.fillMaxSize().layerBackdrop(contentBackdrop)) {
                Aurora(Modifier.fillMaxSize().layerBackdrop(backgroundBackdrop))
                content()
            }
            overlay()
        }
    }
}

/**
 * Four soft light pools — static (no animation, no battery cost). Placed so the tab bar (bottom) and the
 * hero area (upper third) always have colour behind them to refract.
 */
@Composable
fun Aurora(modifier: Modifier = Modifier) {
    val c = SpendWise.colors
    Box(
        modifier
            .background(c.background)
            .drawBehind {
                fun pool(color: Color, x: Float, y: Float, r: Float) = drawRect(
                    Brush.radialGradient(
                        colors = listOf(color, color.copy(alpha = color.alpha * 0.35f), color.copy(alpha = 0f)),
                        center = Offset(size.width * x, size.height * y),
                        radius = size.maxDimension * r,
                    ),
                )
                pool(c.aurora1, 0.05f, 0.14f, 0.60f)
                pool(c.aurora2, 1.0f, 0.42f, 0.55f)
                pool(c.aurora3, 0.0f, 0.80f, 0.50f)
                pool(c.aurora4, 0.60f, 1.02f, 0.50f)
            },
    )
}

enum class GlassKind {
    /** Floating controls: clear, strong lens. */
    Control,
    /** Content cards: more tint so text and money stay crisp. */
    Card,
}

/**
 * Liquid glass surface. [backdrop] defaults by kind: controls refract the whole screen, cards refract the aurora.
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape = RoundedRectangle(Radius.card),
    kind: GlassKind = GlassKind.Card,
    backdrop: Backdrop? = if (kind == GlassKind.Control) LocalContentBackdrop.current else LocalBackgroundBackdrop.current,
    tint: Color? = null,
    lensHeight: Dp = if (kind == GlassKind.Control) 22.dp else 20.dp,
    lensAmount: Dp = if (kind == GlassKind.Control) 32.dp else 36.dp,
    blurRadius: Dp = if (kind == GlassKind.Control) 4.dp else 12.dp,
): Modifier {
    val colors = SpendWise.colors
    if (backdrop == null) return fallbackGlass(shape)
    val allowed = LocalBlurAllowed.current
    val fill = tint ?: when {
        !allowed -> colors.glassTintFallback
        kind == GlassKind.Control -> colors.glassTint
        else -> colors.glassCard
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (allowed) {
                vibrancy()
                blur(blurRadius.toPx())
                lens(lensHeight.toPx(), lensAmount.toPx())
            }
        },
        highlight = { Highlight.Default.copy(alpha = if (colors.isDark) 0.9f else 1f) },
        shadow = { Shadow(radius = 24.dp, color = Color.Black.copy(alpha = if (colors.isDark) 0.35f else 0.10f)) },
        onDrawSurface = { drawRect(fill) },
    )
}

/** Used only when no backdrop exists (e.g. previews): solid surface + hairline. */
@Composable
private fun Modifier.fallbackGlass(shape: Shape): Modifier {
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

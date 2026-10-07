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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.spendwise.app.ui.theme.Radius
import com.spendwise.app.ui.theme.SpendWise
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/*
 * "Light-glass" design system (ADR-007).
 *  - Live blur only where content scrolls beneath: the bottom-nav pill and the collapsed top bar (≤ 2 layers).
 *  - Everything else gets glass from light: tint + 1dp top-edge highlight + ambient glow behind.
 *  - Money never sits on a blurred surface.
 */

/** One HazeState per screen scaffold; glass bars read it from here. */
val LocalGlassState = staticCompositionLocalOf<HazeState?> { null }

/** Whether live blur is allowed right now. False → identical-looking tinted fallback. */
val LocalBlurAllowed = staticCompositionLocalOf { true }

/**
 * Screen root: static ambient light + content marked as the blur source + overlay slot for glass bars.
 * The ambient layer is inside the source so blur has something to refract over AMOLED black.
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    reduceTransparency: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = rememberHazeState()
    val blurAllowed = rememberBlurAllowed(reduceTransparency)
    CompositionLocalProvider(LocalGlassState provides hazeState, LocalBlurAllowed provides blurAllowed) {
        Box(modifier.fillMaxSize().background(SpendWise.colors.background)) {
            Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                AmbientLight()
                content()
            }
            overlay()
        }
    }
}

/** Two baked radial gradients — static (no animation, no battery cost). */
@Composable
fun AmbientLight(modifier: Modifier = Modifier) {
    val colors = SpendWise.colors
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(colors.ambientPrimary, colors.ambientPrimary.copy(alpha = 0f)),
                        center = Offset(size.width * 0.1f, size.height * 0.08f),
                        radius = size.maxDimension * 0.6f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(colors.ambientSecondary, colors.ambientSecondary.copy(alpha = 0f)),
                        center = Offset(size.width * 0.95f, size.height * 0.55f),
                        radius = size.maxDimension * 0.55f,
                    ),
                )
            },
    )
}

/**
 * Live-blurred glass for floating bars. Falls back to an opaque-enough tint when blur is not allowed,
 * chosen so the two states look nearly identical.
 */
@Composable
fun Modifier.liveGlass(shape: Shape, state: HazeState? = LocalGlassState.current): Modifier {
    if (state == null) return lightGlass(shape)
    val colors = SpendWise.colors
    val blurAllowed = LocalBlurAllowed.current
    val style = remember(colors, blurAllowed) {
        HazeBlurStyle {
            blurEnabled(blurAllowed)
            blurRadius(20.dp)
            noiseFactor(0.06f)
            backgroundColor(colors.background)
            colorEffects(listOf(HazeColorEffect.tint(colors.glassTint)))
            fallbackColorEffect(HazeColorEffect.tint(colors.glassTintFallback))
        }
    }
    return this
        .clip(shape)
        .hazeBlur(
            // Financial content: never keep a stale captured frame around.
            input = HazeInput.Sources(state, retention = HazeSourceRetention.ClearWhenUnavailable),
            style = style,
        )
        .glassEdge(shape)
}

/** Glass made of light only — no blur. Used for the hero card, FAB, review cards. */
@Composable
fun Modifier.lightGlass(shape: Shape = RoundedCornerShape(Radius.card)): Modifier {
    val colors = SpendWise.colors
    return this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    colors.surface2.copy(alpha = if (colors.isDark) 0.92f else 1f),
                    colors.surface1.copy(alpha = if (colors.isDark) 0.96f else 1f),
                ),
            ),
        )
        .glassEdge(shape)
}

/** The 1dp top-edge highlight that does most of the "glass" work at almost no cost. */
@Composable
fun Modifier.glassEdge(shape: Shape): Modifier {
    val colors = SpendWise.colors
    return border(
        width = 1.dp,
        brush = Brush.verticalGradient(listOf(colors.glassHighlightTop, colors.glassHighlightBottom, colors.glassHairline)),
        shape = shape,
    )
}

/**
 * Blur is disabled under battery saver, when the system animator scale is 0, or when the user turned on
 * "Reduce transparency" (ADR-007). Battery saver changes are observed live.
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

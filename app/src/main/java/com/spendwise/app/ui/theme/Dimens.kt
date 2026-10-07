package com.spendwise.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/** 4dp grid; 20dp screen margins (ADR-007). */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val screen = 20.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

object Radius {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val group = 20.dp
    val card = 24.dp
    val sheet = 32.dp
}

val SpendWiseShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.card),
    extraLarge = RoundedCornerShape(Radius.sheet),
)

/** Calm, precise motion: three durations, emphasized easing; springs only for direct manipulation. */
@Immutable
data class MotionTokens(
    val short: Int = 150,
    val medium: Int = 250,
    val long: Int = 350,
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f),
    val emphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f),
    /** True when the system "Remove animations" / animator scale 0 is set: everything becomes a ≤100ms fade. */
    val reduced: Boolean = false,
) {
    fun duration(ms: Int): Int = if (reduced) minOf(ms, 100) else ms
}

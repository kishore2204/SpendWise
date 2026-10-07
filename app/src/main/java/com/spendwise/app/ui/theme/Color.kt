package com.spendwise.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Brand tokens (ADR-007). Brand meanings — income, expense, transfer, pending, glass — live here,
 * never in Material roles. Material's ColorScheme is derived from these.
 */
@Immutable
data class SpendWiseColors(
    val isDark: Boolean,
    val background: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    /** Fill on top of the refracted backdrop for floating controls (tab bar, buttons) — iOS-clear. */
    val glassTint: Color,
    /** Fill used when refraction/blur is off (battery saver, reduce transparency). */
    val glassTintFallback: Color,
    val glassHighlightTop: Color,
    val glassHighlightBottom: Color,
    val glassHairline: Color,
    /** Aurora light pools behind everything — what the liquid glass refracts (ADR-008). */
    val aurora1: Color,
    val aurora2: Color,
    val aurora3: Color,
    val aurora4: Color,
    /** Fill drawn on top of refracted backdrop for content cards (more opaque than bars, for text). */
    val glassCard: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val income: Color,
    val expense: Color,
    val transfer: Color,
    val pending: Color,
    val danger: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    val divider: Color,
    val scrim: Color,
    val shadow: Color,
)

val DarkColors = SpendWiseColors(
    isDark = true,
    background = Color(0xFF030305),
    surface1 = Color(0xFF0E0E14),
    surface2 = Color(0xFF16161F),
    surface3 = Color(0xFF1E1E2A),
    glassTint = Color(0x1F101018),
    glassTintFallback = Color(0xF0141322),
    glassHighlightTop = Color(0x24FFFFFF),
    glassHighlightBottom = Color(0x08FFFFFF),
    glassHairline = Color(0x14FFFFFF),
    aurora1 = Color(0xA66E5BFF),
    aurora2 = Color(0x8C3B6BFF),
    aurora3 = Color(0x702EC4B6),
    aurora4 = Color(0x8CB04CFF),
    glassCard = Color(0x47101018),
    primary = Color(0xFFB7A6FF),
    onPrimary = Color(0xFF1A1240),
    primaryContainer = Color(0xFF2E2560),
    onPrimaryContainer = Color(0xFFE7E1FF),
    income = Color(0xFF4FD1B0),
    expense = Color(0xFFFF9A6B),
    transfer = Color(0xFF8FB4FF),
    pending = Color(0xFFF5C451),
    danger = Color(0xFFFF7A85),
    text1 = Color(0xFFF2F1F7),
    text2 = Color(0xFFA9A7B8),
    text3 = Color(0xFF6E6C7E),
    divider = Color(0x14FFFFFF),
    scrim = Color(0x8C000000),
    shadow = Color(0x00000000),
)

val LightColors = SpendWiseColors(
    isDark = false,
    background = Color(0xFFF6F5FA),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEFEDF6),
    surface3 = Color(0xFFE6E3F0),
    glassTint = Color(0x40FAFAFA),
    glassTintFallback = Color(0xF7FFFFFF),
    glassHighlightTop = Color(0xFFFFFFFF),
    glassHighlightBottom = Color(0x99FFFFFF),
    glassHairline = Color(0x0F1B1530),
    aurora1 = Color(0x99B9A8FF),
    aurora2 = Color(0x809EC5FF),
    aurora3 = Color(0x66A8F0E0),
    aurora4 = Color(0x66F3B8FF),
    glassCard = Color(0x73FFFFFF),
    primary = Color(0xFF5B43D6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7E1FF),
    onPrimaryContainer = Color(0xFF1F1260),
    income = Color(0xFF0B8468),
    expense = Color(0xFFC0521A),
    transfer = Color(0xFF2F62D9),
    pending = Color(0xFF9A6400),
    danger = Color(0xFFC62839),
    text1 = Color(0xFF15131F),
    text2 = Color(0xFF55526A),
    text3 = Color(0xFF85829A),
    divider = Color(0x141B1530),
    scrim = Color(0x4D15131F),
    shadow = Color(0x141B1530),
)

/** Linear blend used for the theme-switch crossfade (one animated fraction instead of ~25 animations). */
fun lerp(from: SpendWiseColors, to: SpendWiseColors, fraction: Float): SpendWiseColors {
    if (fraction <= 0f) return from
    if (fraction >= 1f) return to
    fun c(a: Color, b: Color) = lerp(a, b, fraction)
    return SpendWiseColors(
        isDark = if (fraction < 0.5f) from.isDark else to.isDark,
        background = c(from.background, to.background),
        surface1 = c(from.surface1, to.surface1),
        surface2 = c(from.surface2, to.surface2),
        surface3 = c(from.surface3, to.surface3),
        glassTint = c(from.glassTint, to.glassTint),
        glassTintFallback = c(from.glassTintFallback, to.glassTintFallback),
        glassHighlightTop = c(from.glassHighlightTop, to.glassHighlightTop),
        glassHighlightBottom = c(from.glassHighlightBottom, to.glassHighlightBottom),
        glassHairline = c(from.glassHairline, to.glassHairline),
        aurora1 = c(from.aurora1, to.aurora1),
        aurora2 = c(from.aurora2, to.aurora2),
        aurora3 = c(from.aurora3, to.aurora3),
        aurora4 = c(from.aurora4, to.aurora4),
        glassCard = c(from.glassCard, to.glassCard),
        primary = c(from.primary, to.primary),
        onPrimary = c(from.onPrimary, to.onPrimary),
        primaryContainer = c(from.primaryContainer, to.primaryContainer),
        onPrimaryContainer = c(from.onPrimaryContainer, to.onPrimaryContainer),
        income = c(from.income, to.income),
        expense = c(from.expense, to.expense),
        transfer = c(from.transfer, to.transfer),
        pending = c(from.pending, to.pending),
        danger = c(from.danger, to.danger),
        text1 = c(from.text1, to.text1),
        text2 = c(from.text2, to.text2),
        text3 = c(from.text3, to.text3),
        divider = c(from.divider, to.divider),
        scrim = c(from.scrim, to.scrim),
        shadow = c(from.shadow, to.shadow),
    )
}

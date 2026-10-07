package com.spendwise.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Brand tokens (ADR-009). A deliberately small palette: a neutral grey ramp, ONE accent, and income/expense
 * hues that appear only where money does. Glass is always neutral — colour only ever comes from content.
 * Contrast targets: text1 ≥ 12:1, text2 ≥ 6:1, hero balance ≥ 7:1 worst case. text3 is for disabled/meta only.
 */
@Immutable
data class SpendWiseColors(
    val isDark: Boolean,
    val background: Color,
    /** A single neutral soft light at the top of the screen — depth without hue. */
    val backgroundLight: Color,
    /** Guilloché line colour at the top-right: a light lavender touch (ADR-010). */
    val patternAccent: Color,
    /** Guilloché line colour at the bottom-left: neutral. The lines blend linearly between the two. */
    val pattern: Color,
    /** Fill for content cards made of glass (more than controls, so text stays ≥ 4.5:1). */
    val glassCard: Color,
    /** Solid content cards and grouped lists. */
    val surface1: Color,
    /** Raised elements inside cards (segmented thumb, inputs). */
    val surface2: Color,
    /** Tracks and wells inside cards. */
    val surface3: Color,
    /** Floating glass controls (tab bar): neutral fill over the refracted content. */
    val glassTint: Color,
    /** Same, used when refraction is off (battery saver, reduce transparency). */
    val glassTintFallback: Color,
    /** Specular rim at the top of glass and cards. */
    val glassHighlightTop: Color,
    val glassHighlightBottom: Color,
    /** Hairline that defines edges — what makes light-theme glass visible. */
    val glassHairline: Color,
    /** Resting selection droplet fill. */
    val droplet: Color,
    /** Lavender glass tint for the primary floating action (+). */
    val accentGlass: Color,
    /** Glyph on the lavender glass. */
    val onAccentGlass: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val income: Color,
    val expense: Color,
    /** Transfers are neutral — they are neither income nor expense. */
    val transfer: Color,
    val pending: Color,
    val danger: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    /** Monochrome icon badge fill. */
    val badge: Color,
    val divider: Color,
    val scrim: Color,
    /** Shadow colour for cards and glass. */
    val shadow: Color,
)

val DarkColors = SpendWiseColors(
    isDark = true,
    background = Color(0xFF050507),
    backgroundLight = Color(0xFF17171B),
    patternAccent = Color(0x667B6CF0),
    pattern = Color(0x14FFFFFF),
    glassCard = Color(0x80121216),
    surface1 = Color(0xFF141417),
    surface2 = Color(0xFF2A2A30),
    surface3 = Color(0xFF1C1C20),
    glassTint = Color(0x5917171B),
    glassTintFallback = Color(0xF214141A),
    glassHighlightTop = Color(0x33FFFFFF),
    glassHighlightBottom = Color(0x0AFFFFFF),
    glassHairline = Color(0x1AFFFFFF),
    droplet = Color(0x1FFFFFFF),
    accentGlass = Color(0x80C4B9FF),
    onAccentGlass = Color(0xFFFFFFFF),
    primary = Color(0xFFA99BFF),
    onPrimary = Color(0xFF121218),
    primaryContainer = Color(0x29A99BFF),
    onPrimaryContainer = Color(0xFFE4DEFF),
    income = Color(0xFF4CD4A0),
    expense = Color(0xFFFF7A5C),
    transfer = Color(0xFFA6A5AB),
    pending = Color(0xFFE9B949),
    danger = Color(0xFFFF7A5C),
    text1 = Color(0xFFF4F4F7),
    text2 = Color(0xFFA6A5AB),
    text3 = Color(0xFF6B6B73),
    badge = Color(0x14FFFFFF),
    divider = Color(0x14FFFFFF),
    scrim = Color(0x99000000),
    shadow = Color(0x80000000),
)

val LightColors = SpendWiseColors(
    isDark = false,
    background = Color(0xFFEDEDF0),
    backgroundLight = Color(0xFFF7F7F9),
    patternAccent = Color(0x4D4A35C2),
    pattern = Color(0x17111117),
    glassCard = Color(0x33FFFFFF),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFFFFFFF),
    surface3 = Color(0xFFE6E6EA),
    glassTint = Color(0x29FAFAFC),
    glassTintFallback = Color(0xF7FAFAFC),
    glassHighlightTop = Color(0xFFFFFFFF),
    glassHighlightBottom = Color(0x66FFFFFF),
    glassHairline = Color(0x1A111117),
    droplet = Color(0x80FFFFFF),
    accentGlass = Color(0x73C9BEFF),
    onAccentGlass = Color(0xFF3F2AB5),
    primary = Color(0xFF5038C8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0x1F5038C8),
    onPrimaryContainer = Color(0xFF2A1C78),
    income = Color(0xFF00704F),
    expense = Color(0xFFA8321A),
    transfer = Color(0xFF55545A),
    pending = Color(0xFF8A5A00),
    danger = Color(0xFFA8321A),
    text1 = Color(0xFF111117),
    text2 = Color(0xFF55545A),
    text3 = Color(0xFF8E8E96),
    badge = Color(0x0F111117),
    divider = Color(0x14111117),
    scrim = Color(0x52111117),
    shadow = Color(0x24111117),
)

/** Linear blend used for the theme-switch crossfade (one animated fraction instead of many animations). */
fun lerp(from: SpendWiseColors, to: SpendWiseColors, fraction: Float): SpendWiseColors {
    if (fraction <= 0f) return from
    if (fraction >= 1f) return to
    fun c(a: Color, b: Color) = lerp(a, b, fraction)
    return SpendWiseColors(
        isDark = if (fraction < 0.5f) from.isDark else to.isDark,
        background = c(from.background, to.background),
        backgroundLight = c(from.backgroundLight, to.backgroundLight),
        patternAccent = c(from.patternAccent, to.patternAccent),
        pattern = c(from.pattern, to.pattern),
        glassCard = c(from.glassCard, to.glassCard),
        surface1 = c(from.surface1, to.surface1),
        surface2 = c(from.surface2, to.surface2),
        surface3 = c(from.surface3, to.surface3),
        glassTint = c(from.glassTint, to.glassTint),
        glassTintFallback = c(from.glassTintFallback, to.glassTintFallback),
        glassHighlightTop = c(from.glassHighlightTop, to.glassHighlightTop),
        glassHighlightBottom = c(from.glassHighlightBottom, to.glassHighlightBottom),
        glassHairline = c(from.glassHairline, to.glassHairline),
        droplet = c(from.droplet, to.droplet),
        accentGlass = c(from.accentGlass, to.accentGlass),
        onAccentGlass = c(from.onAccentGlass, to.onAccentGlass),
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
        badge = c(from.badge, to.badge),
        divider = c(from.divider, to.divider),
        scrim = c(from.scrim, to.scrim),
        shadow = c(from.shadow, to.shadow),
    )
}

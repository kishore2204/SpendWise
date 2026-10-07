package com.spendwise.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.spendwise.app.data.preferences.ThemeMode

val LocalSpendWiseColors = staticCompositionLocalOf { DarkColors }
val LocalMoneyTypography = staticCompositionLocalOf { SpendWiseMoneyTypography }
val LocalMotion = staticCompositionLocalOf { MotionTokens() }

/** Access point for brand tokens: `SpendWise.colors.income`, `SpendWise.money.hero`, … */
object SpendWise {
    val colors: SpendWiseColors
        @Composable @ReadOnlyComposable get() = LocalSpendWiseColors.current
    val money: MoneyTypography
        @Composable @ReadOnlyComposable get() = LocalMoneyTypography.current
    val motion: MotionTokens
        @Composable @ReadOnlyComposable get() = LocalMotion.current
}

@Composable
fun ThemeMode.isDarkFor(): Boolean = when (this) {
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
    ThemeMode.System -> isSystemInDarkTheme()
}

@Composable
fun SpendWiseTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val motion = remember(reducedMotion) { MotionTokens(reduced = reducedMotion) }

    // Theme switch: a single 250ms crossfade of every token (ADR-007).
    val darkFraction by animateFloatAsState(
        targetValue = if (darkTheme) 1f else 0f,
        animationSpec = tween(motion.duration(motion.medium), easing = motion.emphasized),
        label = "theme",
    )
    val colors = lerp(LightColors, DarkColors, darkFraction)

    CompositionLocalProvider(
        LocalSpendWiseColors provides colors,
        LocalMoneyTypography provides SpendWiseMoneyTypography,
        LocalMotion provides motion,
    ) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = SpendWiseTypography,
            shapes = SpendWiseShapes,
            content = content,
        )
    }
}

/** Material Your dynamic colour stays off: the brand violet is the identity (ADR-007). */
private fun SpendWiseColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = primaryContainer,
        onSecondaryContainer = onPrimaryContainer,
        background = background,
        onBackground = text1,
        surface = surface1,
        onSurface = text1,
        surfaceVariant = surface2,
        onSurfaceVariant = text2,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface1,
        surfaceContainer = surface2,
        surfaceContainerHigh = surface3,
        surfaceContainerHighest = surface3,
        surfaceBright = surface3,
        surfaceDim = background,
        outline = text3,
        outlineVariant = divider,
        error = danger,
        scrim = scrim,
    )
}

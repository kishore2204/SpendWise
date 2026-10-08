package com.spendwise.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.spendwise.app.R

/* Comfortaa is the application face. Android's normal font fallback supplies scripts it does not contain. */
private fun comfortaaWeight(weight: Int) = Font(
    resId = R.font.comfortaa_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val ComfortaaFamily = FontFamily(
    comfortaaWeight(300),
    comfortaaWeight(400),
    comfortaaWeight(500),
    comfortaaWeight(600),
    comfortaaWeight(700),
)
/** Keep formatted financial labels in Comfortaa instead of falling back to the system family. */
fun TextStyle.financialFigures(): TextStyle = copy(
    fontFamily = ComfortaaFamily,
    fontFeatureSettings = null,
)

val SpendWiseTypography = Typography(
    displayLarge = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.Bold, fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = (-0.02).em),
    displaySmall = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = (-0.02).em),
    headlineLarge = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.01.em),
)

@Immutable
data class MoneyTypography(
    /** Hero balance. Long values shrink toward [heroMinSize]; ₹ is slightly smaller with a thin gap (see MoneyText). */
    val hero: TextStyle,
    val large: TextStyle,
    val row: TextStyle,
    val small: TextStyle,
) {
    val heroMinSize = 32.sp
}

val SpendWiseMoneyTypography = MoneyTypography(
    hero = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-0.015).em),
    large = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    row = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    small = TextStyle(fontFamily = ComfortaaFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
)

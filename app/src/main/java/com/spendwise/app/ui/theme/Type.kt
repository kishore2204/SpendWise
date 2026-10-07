package com.spendwise.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * ADR-007 type scale. Phase 2 uses the system font family; the bundled Inter variable font
 * (with verified ₹ and tabular figures) is a Phase 8 decision. Every money style sets "tnum"
 * so digits never jitter when values change.
 */

private val Family = FontFamily.Default
private const val TABULAR = "tnum"

val SpendWiseTypography = Typography(
    displaySmall = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Family, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Family, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Family, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Family, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = Family, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.02.em),
)

@Immutable
data class MoneyTypography(
    /** Hero balance. Long values shrink toward [heroMinSize]; ₹ and paise render smaller (see MoneyText). */
    val hero: TextStyle,
    val large: TextStyle,
    val row: TextStyle,
    val small: TextStyle,
) {
    val heroMinSize = 32.sp
}

val SpendWiseMoneyTypography = MoneyTypography(
    hero = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-0.015).em, fontFeatureSettings = TABULAR),
    large = TextStyle(fontFamily = Family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, fontFeatureSettings = TABULAR),
    row = TextStyle(fontFamily = Family, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, fontFeatureSettings = TABULAR),
    small = TextStyle(fontFamily = Family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TABULAR),
)

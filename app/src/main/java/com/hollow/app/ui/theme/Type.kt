package com.hollow.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

// ============================================================================
// System Font & Type Scale (M3 Default * 0.9 Scaling, Zero Custom Fonts)
// ============================================================================

// Aliases mapped directly to System default and monospace fonts
val JakartaFontFamily: FontFamily = FontFamily.Default
val FrauncesFontFamily: FontFamily = FontFamily.Default
val JetBrainsMonoFontFamily: FontFamily = FontFamily.Monospace

private fun TextStyle.scale(factor: Float = 0.9f): TextStyle {
    return this.copy(
        fontFamily = FontFamily.Default,
        fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight
    )
}

private val defaultM3Typography = Typography()

val Typography = Typography(
    displayLarge = defaultM3Typography.displayLarge.scale(),
    displayMedium = defaultM3Typography.displayMedium.scale(),
    displaySmall = defaultM3Typography.displaySmall.scale(),
    headlineLarge = defaultM3Typography.headlineLarge.scale(),
    headlineMedium = defaultM3Typography.headlineMedium.scale(),
    headlineSmall = defaultM3Typography.headlineSmall.scale(),
    titleLarge = defaultM3Typography.titleLarge.scale(),
    titleMedium = defaultM3Typography.titleMedium.scale(),
    titleSmall = defaultM3Typography.titleSmall.scale(),
    bodyLarge = defaultM3Typography.bodyLarge.scale(),
    bodyMedium = defaultM3Typography.bodyMedium.scale(),
    bodySmall = defaultM3Typography.bodySmall.scale(),
    labelLarge = defaultM3Typography.labelLarge.scale(),
    labelMedium = defaultM3Typography.labelMedium.scale(),
    labelSmall = defaultM3Typography.labelSmall.scale()
)

// Emphasized type variants
val Typography.titleLargeEmphasized: TextStyle
    get() = titleLarge.copy(fontWeight = FontWeight.SemiBold)

val Typography.headlineSmallEmphasized: TextStyle
    get() = headlineSmall.copy(fontWeight = FontWeight.SemiBold)

val Typography.displaySmallEmphasized: TextStyle
    get() = displaySmall.copy(fontWeight = FontWeight.SemiBold)

// Monospace text style for code, keys, and diagnostics
val MonospaceCodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.6.sp,
    lineHeight = 18.sp
)

package com.hollow.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ============================================================================
// Monochrome Material 3 Schemes
// ============================================================================

private val IsoVaultLightScheme = lightColorScheme(
    primary = MonoPrimaryLight,
    onPrimary = MonoOnPrimaryLight,
    primaryContainer = MonoPrimaryContainerLight,
    onPrimaryContainer = MonoOnPrimaryContainerLight,

    secondary = MonoSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = MonoSecondaryContainerLight,
    onSecondaryContainer = MonoSecondaryLight,

    tertiary = MonoTertiaryLight,
    onTertiary = Color.White,
    tertiaryContainer = MonoTertiaryContainerLight,
    onTertiaryContainer = MonoSecondaryLight,

    background = MonoBackgroundLight,
    onBackground = MonoOnBackgroundLight,
    surface = MonoSurfaceLight,
    onSurface = MonoOnSurfaceLight,
    surfaceVariant = MonoSurfaceVariantLight,
    onSurfaceVariant = MonoOnSurfaceVariantLight,

    surfaceContainerLowest = MonoSurfaceContainerLowestLight,
    surfaceContainerLow = MonoSurfaceContainerLowLight,
    surfaceContainer = MonoSurfaceContainerLight,
    surfaceContainerHigh = MonoSurfaceContainerHighLight,
    surfaceContainerHighest = MonoSurfaceContainerHighestLight,

    outline = MonoOutlineLight,
    outlineVariant = MonoOutlineVariantLight,
    inverseSurface = MonoInverseSurfaceLight,
    inverseOnSurface = Color.White,

    error = MonoErrorLight,
    onError = Color.White,
    errorContainer = MonoErrorContainerLight,
    onErrorContainer = MonoOnErrorContainerLight
)

private val IsoVaultDarkScheme = darkColorScheme(
    primary = MonoPrimaryDark,
    onPrimary = MonoOnPrimaryDark,
    primaryContainer = MonoPrimaryContainerDark,
    onPrimaryContainer = MonoOnPrimaryContainerDark,

    secondary = MonoSecondaryDark,
    onSecondary = MonoOnPrimaryDark,
    secondaryContainer = MonoSecondaryContainerDark,
    onSecondaryContainer = Color.White,

    tertiary = MonoTertiaryDark,
    onTertiary = MonoOnPrimaryDark,
    tertiaryContainer = MonoTertiaryContainerDark,
    onTertiaryContainer = Color.White,

    background = MonoBackgroundDark,
    onBackground = MonoOnBackgroundDark,
    surface = MonoSurfaceDark,
    onSurface = MonoOnSurfaceDark,
    surfaceVariant = MonoSurfaceVariantDark,
    onSurfaceVariant = MonoOnSurfaceVariantDark,

    surfaceContainerLowest = MonoSurfaceContainerLowestDark,
    surfaceContainerLow = MonoSurfaceContainerLowDark,
    surfaceContainer = MonoSurfaceContainerDark,
    surfaceContainerHigh = MonoSurfaceContainerHighDark,
    surfaceContainerHighest = MonoSurfaceContainerHighestDark,

    outline = MonoOutlineDark,
    outlineVariant = MonoOutlineVariantDark,
    inverseSurface = MonoInverseSurfaceDark,
    inverseOnSurface = MonoOnPrimaryDark,

    error = MonoErrorDark,
    onError = Color.White,
    errorContainer = MonoErrorContainerDark,
    onErrorContainer = MonoOnErrorContainerDark
)

// ============================================================================
// Shapes: 4 · 8 · 12 · 16 · 28 dp
// ============================================================================

val IsoVaultProShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

val ShapeCardExtraLarge = RoundedCornerShape(28.dp)
val ShapeTileLarge = RoundedCornerShape(16.dp)
val ShapeThumbnailMedium = RoundedCornerShape(12.dp)
val ShapeFloatingBar = RoundedCornerShape(32.dp)
val ShapeBulkActionBar = RoundedCornerShape(28.dp)

/**
 * Grouped list row corner helper:
 * Outer corner 24dp, inner corner 6dp, gap 2dp.
 */
fun groupedRowShape(isTop: Boolean, isBottom: Boolean): RoundedCornerShape {
    val topRadius = if (isTop) 24.dp else 6.dp
    val bottomRadius = if (isBottom) 24.dp else 6.dp
    return RoundedCornerShape(topRadius, topRadius, bottomRadius, bottomRadius)
}

@Composable
fun IsoVaultTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> IsoVaultDarkScheme
        else -> IsoVaultLightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = IsoVaultProShapes,
        content = content
    )
}

val AppLightColorScheme = IsoVaultLightScheme
val AppDarkColorScheme = IsoVaultDarkScheme
val AppShapes = IsoVaultProShapes

@Composable
fun AppTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) = IsoVaultTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) = IsoVaultTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)

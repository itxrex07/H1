package com.hollow.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// Monochrome Black & White Palette (M3 Expressive Spec)
// ============================================================================

// Light Scheme Roles
val MonoPrimaryLight = Color(0xFF000000)
val MonoOnPrimaryLight = Color(0xFFFFFFFF)
val MonoPrimaryContainerLight = Color(0xFF000000)
val MonoOnPrimaryContainerLight = Color(0xFFFFFFFF)

val MonoSecondaryLight = Color(0xFF111827)
val MonoSecondaryContainerLight = Color(0xFFF3F4F6)

val MonoTertiaryLight = Color(0xFF4B5563)
val MonoTertiaryContainerLight = Color(0xFFE5E7EB)

val MonoErrorLight = Color(0xFFDC2626)
val MonoErrorContainerLight = Color(0xFFFEE2E2)
val MonoOnErrorContainerLight = Color(0xFF7F1D1D)

val MonoBackgroundLight = Color(0xFFF8FAFC)
val MonoSurfaceLight = Color(0xFFFFFFFF)
val MonoSurfaceVariantLight = Color(0xFFF3F4F6)
val MonoOnSurfaceLight = Color(0xFF111827)
val MonoOnBackgroundLight = Color(0xFF111827)
val MonoOnSurfaceVariantLight = Color(0xFF6B7280)

val MonoOutlineLight = Color(0xFF9CA3AF)
val MonoOutlineVariantLight = Color(0xFFE5E7EB)

val MonoSurfaceContainerLowestLight = Color(0xFFFFFFFF)
val MonoSurfaceContainerLowLight = Color(0xFFF9FAFB)
val MonoSurfaceContainerLight = Color(0xFFF3F4F6)
val MonoSurfaceContainerHighLight = Color(0xFFE5E7EB)
val MonoSurfaceContainerHighestLight = Color(0xFFE5E7EB)
val MonoInverseSurfaceLight = Color(0xFF111111)

// Dark Scheme Roles
val MonoPrimaryDark = Color(0xFFFFFFFF)
val MonoOnPrimaryDark = Color(0xFF0A0A0A)
val MonoPrimaryContainerDark = Color(0xFFFFFFFF)
val MonoOnPrimaryContainerDark = Color(0xFF0A0A0A)

val MonoSecondaryDark = Color(0xFFE5E7EB)
val MonoSecondaryContainerDark = Color(0xFF262626)

val MonoTertiaryDark = Color(0xFFA3A3A3)
val MonoTertiaryContainerDark = Color(0xFF1F1F1F)

val MonoErrorDark = Color(0xFFEF4444)
val MonoErrorContainerDark = Color(0xFF450A0A)
val MonoOnErrorContainerDark = Color(0xFFFECACA)

val MonoBackgroundDark = Color(0xFF0A0A0A)
val MonoSurfaceDark = Color(0xFF111111)
val MonoSurfaceVariantDark = Color(0xFF171717)
val MonoOnSurfaceDark = Color(0xFFFFFFFF)
val MonoOnBackgroundDark = Color(0xFFFFFFFF)
val MonoOnSurfaceVariantDark = Color(0xFF9CA3AF)

val MonoOutlineDark = Color(0xFF404040)
val MonoOutlineVariantDark = Color(0xFF262626)

val MonoSurfaceContainerLowestDark = Color(0xFF0A0A0A)
val MonoSurfaceContainerLowDark = Color(0xFF111111)
val MonoSurfaceContainerDark = Color(0xFF171717)
val MonoSurfaceContainerHighDark = Color(0xFF1F1F1F)
val MonoSurfaceContainerHighestDark = Color(0xFF262626)
val MonoInverseSurfaceDark = Color(0xFFFFFFFF)

// Window background (splash) for both modes
val WindowSplashBackground = Color(0xFF0A0A0A)

// Chart & Category Accents (Max 15° hue-shift harmonize)
val ChartBlue = Color(0xFF4F86F7)
val ChartViolet = Color(0xFF9B5DE5)
val ChartTeal = Color(0xFF00BFA6)
val ChartAmber = Color(0xFFF4A62A)
val ChartRose = Color(0xFFF2617A)
val ChartSlate = Color(0xFF8A93A6)

object ChartPalette {
    val Blue = ChartBlue
    val Violet = ChartViolet
    val Teal = ChartTeal
    val Amber = ChartAmber
    val Rose = ChartRose
    val Slate = ChartSlate
}

// Status Dots
val StatusConnected = Color(0xFF4CAF50)
val StatusWorking = Color(0xFFFFA726)
val StatusOffline = Color(0xFFEF4444)

// Alpha Tokens
object AlphaTokens {
    const val ICON_BLOB_BG = 0.12f
    const val HERO_SECONDARY_TEXT = 0.80f
    const val OUTLINE_BORDER = 0.50f
    const val DISABLED_ICON = 0.38f
    const val GRID_BADGE = 0.60f
}

// Backward Compatibility Aliases for Existing App Screens
val ProEmeraldPrimary = MonoPrimaryLight
val ProEmeraldDark = Color(0xFF111111)
val ProEmeraldLight = MonoPrimaryDark
val ProEmeraldContainer = MonoSecondaryContainerLight
val ProEmeraldContainerDark = MonoSecondaryContainerDark

val ProIndigoSecondary = MonoSecondaryLight
val ProIndigoContainer = MonoSecondaryContainerLight
val ProIndigoDark = MonoSecondaryDark
val ProIndigoContainerDark = MonoSecondaryContainerDark

val ProAmberTertiary = ChartAmber
val ProAmberContainer = Color(0xFFFEF3C7)
val ProAmberDark = ChartAmber
val ProAmberContainerDark = Color(0xFF3D2808)

val ProCoralError = MonoErrorLight
val ProCoralContainer = MonoErrorContainerLight
val ProCoralDark = MonoErrorDark
val ProCoralContainerDark = MonoErrorContainerDark

val ProVioletAccent = ChartViolet
val ProVioletContainer = Color(0xFFEDE9FE)
val ProCyanAccent = ChartTeal

val ProBackgroundLight = MonoBackgroundLight
val ProSurfaceLight = MonoSurfaceLight
val ProSurfaceLowLight = MonoSurfaceContainerLowLight
val ProSurfaceContainerLight = MonoSurfaceContainerLight
val ProSurfaceHighLight = MonoSurfaceContainerHighLight
val ProOutlineLight = MonoOutlineLight
val ProOutlineSubtleLight = MonoOutlineVariantLight

val ProBackgroundDark = MonoBackgroundDark
val ProSurfaceDark = MonoSurfaceDark
val ProSurfaceLowDark = MonoSurfaceContainerLowDark
val ProSurfaceContainerDark = MonoSurfaceContainerDark
val ProSurfaceHighDark = MonoSurfaceContainerHighDark
val ProOutlineDark = MonoOutlineDark
val ProOutlineSubtleDark = MonoOutlineVariantDark

val ProInkPrimary = MonoOnSurfaceLight
val ProInkSecondary = MonoOnSurfaceVariantLight
val ProInkTertiary = MonoOutlineLight

val ClaySecondary = MonoErrorLight
val ClaySoft = MonoErrorContainerLight
val LavenderAccent = ChartViolet
val LavenderSoft = Color(0xFFEDE9FE)

val TerminalBlack = Color(0xFF0A0A0A)
val TerminalGreen = Color(0xFF4CAF50)

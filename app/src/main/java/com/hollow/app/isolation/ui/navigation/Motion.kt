package com.hollow.app.isolation.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** All screen transitions used in the app (Material "emphasized" easing, 400ms in / 200ms out). */
object Motion {
    private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    private const val ENTER_MS = 400
    private const val EXIT_MS = 200

    // Push/pop inside a flow (shared-axis X, 1/4 width slide)
    fun sharedAxisEnter(forward: Boolean): EnterTransition =
        slideInHorizontally(tween(ENTER_MS, easing = EmphasizedDecelerate)) { w -> if (forward) w / 4 else -w / 4 } +
                fadeIn(tween(ENTER_MS / 2, delayMillis = ENTER_MS / 8))

    fun sharedAxisExit(forward: Boolean): ExitTransition =
        slideOutHorizontally(tween(EXIT_MS, easing = EmphasizedAccelerate)) { w -> if (forward) -w / 4 else w / 4 } +
                fadeOut(tween(EXIT_MS))

    // Fade-through (scale 0.94)
    fun fadeThroughEnter(): EnterTransition =
        fadeIn(tween(ENTER_MS / 2, delayMillis = EXIT_MS / 2)) +
                scaleIn(initialScale = 0.94f, animationSpec = tween(ENTER_MS, easing = EmphasizedDecelerate))

    fun fadeThroughExit(): ExitTransition =
        fadeOut(tween(EXIT_MS / 2))

    // Bottom-tab peer switch (full-width slide + fade)
    fun tabEnter(forward: Boolean): EnterTransition =
        slideInHorizontally(tween(ENTER_MS, easing = EmphasizedDecelerate)) { w -> if (forward) w else -w } +
                fadeIn(tween(ENTER_MS / 2))

    fun tabExit(forward: Boolean): ExitTransition =
        slideOutHorizontally(tween(ENTER_MS, easing = EmphasizedDecelerate)) { w -> if (forward) -w else w } +
                fadeOut(tween(ENTER_MS / 2))

    // Preview / viewer (scale from 0.86)
    fun previewEnter(): EnterTransition =
        fadeIn(tween(ENTER_MS / 2)) + scaleIn(initialScale = 0.86f, animationSpec = tween(ENTER_MS, easing = EmphasizedDecelerate))

    fun previewExit(): ExitTransition =
        fadeOut(tween(EXIT_MS)) + scaleOut(targetScale = 0.92f, animationSpec = tween(EXIT_MS, easing = EmphasizedAccelerate))
}

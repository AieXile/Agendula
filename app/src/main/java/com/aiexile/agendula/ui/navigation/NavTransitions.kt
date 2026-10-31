package com.aiexile.agendula.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import de.jeanlucmakiola.floret.identity.rememberReduceMotion

/**
 * The app's one definition of how a second-level page arrives and how it
 * leaves, shared by every place that layers one screen over another: the route
 * stack ([AgendulaNavHost]) and the settings hub's own section stack
 * (`SlideInSection`). Screens hand navigation to the shell and never pick their
 * own motion, so the direction is decided here and only here.
 *
 * Timing comes from the active M3 Expressive [MotionScheme] — the spatial and
 * effects springs — rather than a fixed duration, so the pages settle with the
 * same spring physics as the rest of the app and follow a theme that swaps the
 * scheme.
 *
 * Push (a drill-down: the root, a list, a detail → the next page):
 *  - the incoming page slides in from the right, fully opaque;
 *  - the page it covers recedes to 95% and dims, still legible behind the
 *    slide rather than blinking out.
 *
 * Pop (the page's own back: top-bar arrow, system back or gesture):
 *  - the page slides back out to the right, over the page it returns to, so the
 *    return always reads as motion in the opposite direction;
 *  - the page underneath springs back from 95% and fades in at the same time,
 *    so the two movements overlap instead of arriving as one flat cross-fade.
 *
 * Under reduced motion (the platform "remove animations" setting) the spatial
 * movement is dropped and only the fades remain, so the change of page stays
 * legible without the lateral travel.
 */
internal class NavTransitions(
    /** The incoming page's arrival: [NavTransitions] doc, push. */
    val enter: EnterTransition,
    /** The covered page's departure during a push. */
    val exit: ExitTransition,
    /** The page underneath, coming back on a pop. */
    val popEnter: EnterTransition,
    /** The page leaving on a pop. */
    val popExit: ExitTransition,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberNavTransitions(
    reduceMotion: Boolean = rememberReduceMotion(),
): NavTransitions {
    val scheme = MaterialTheme.motionScheme
    return remember(reduceMotion, scheme) { navTransitions(reduceMotion, scheme) }
}

/** Where a covered page settles: a step back, dimmed, but not gone. */
private const val COVERED_SCALE = 0.95f
private const val COVERED_ALPHA = 0.7f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun navTransitions(reduceMotion: Boolean, scheme: MotionScheme): NavTransitions {
    val fade = scheme.defaultEffectsSpec<Float>()
    if (reduceMotion) {
        val fadeIn = fadeIn(animationSpec = fade)
        val fadeOut = fadeOut(animationSpec = fade)
        return NavTransitions(enter = fadeIn, exit = fadeOut, popEnter = fadeIn, popExit = fadeOut)
    }
    // Slides need the offset spec, since the distance is in pixels.
    val slide = scheme.defaultSpatialSpec<IntOffset>()
    val scale = scheme.defaultSpatialSpec<Float>()
    return NavTransitions(
        enter = slideInHorizontally(animationSpec = slide) { it },
        exit = scaleOut(animationSpec = scale, targetScale = COVERED_SCALE) +
            fadeOut(animationSpec = fade, targetAlpha = COVERED_ALPHA),
        popEnter = scaleIn(animationSpec = scale, initialScale = COVERED_SCALE) +
            fadeIn(animationSpec = fade),
        popExit = slideOutHorizontally(animationSpec = slide) { it },
    )
}

package de.jeanlucmakiola.floret.ui.navigation

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset

/**
 * The horizontal slide spec for screen-to-screen navigation: the *fast*
 * spring-physics spec from the active motion scheme — snappy with a subtle
 * springy settle, rather than a fixed easing curve. Same choice as Calendula's
 * calendar slide, so the two apps move the same way.
 *
 * Read in composable scope so the non-composable `NavHost` transition lambdas
 * can capture it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberNavSlideSpec(): FiniteAnimationSpec<IntOffset> =
    MaterialTheme.motionScheme.fastSpatialSpec()

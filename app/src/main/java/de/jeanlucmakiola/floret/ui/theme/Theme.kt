package de.jeanlucmakiola.floret.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * App theme. Honors:
 *   - System light/dark.
 *   - Dynamic Color on API 31+, else falls back to the hand-tuned scheme
 *     derived from [FloretSeed].
 *
 * Mirrors Calendula's theme: MaterialExpressiveTheme routes all component +
 * custom motion through MaterialTheme.motionScheme. STANDARD over expressive()
 * is the same deliberate choice — spring choreography without the overshoot.
 *
 * A Settings screen can later override dynamicColor and theme mode; the M0
 * foundation just follows the system.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FloretTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> FloretDarkFallback
        else -> FloretLightFallback
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = FloretTypography,
        motionScheme = MotionScheme.standard(),
        content = content,
    )
}

package de.jeanlucmakiola.agendula.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import de.jeanlucmakiola.floret.identity.FloretExpressiveTheme

/**
 * Agendula's theme: the family's [FloretExpressiveTheme] machinery (dynamic
 * colour on API 31+, system light/dark, the standard motion scheme) fed with
 * Agendula's own identity — its seed-derived fallback schemes
 * ([AgendulaLightFallback] / [AgendulaDarkFallback]) and [AgendulaTypography].
 * The mechanics live in floret-kit; the look stays here.
 */
@Composable
fun AgendulaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    FloretExpressiveTheme(
        lightScheme = AgendulaLightFallback,
        darkScheme = AgendulaDarkFallback,
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        typography = AgendulaTypography,
        content = content,
    )
}

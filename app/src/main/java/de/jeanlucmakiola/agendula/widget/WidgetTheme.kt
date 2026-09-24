package de.jeanlucmakiola.agendula.widget

import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.material3.ColorProviders
import de.jeanlucmakiola.agendula.data.prefs.ThemeMode
import de.jeanlucmakiola.agendula.ui.theme.AgendulaDarkFallback
import de.jeanlucmakiola.agendula.ui.theme.AgendulaLightFallback

private val AgendulaGlanceColors = ColorProviders(
    light = AgendulaLightFallback,
    dark = AgendulaDarkFallback,
)

/**
 * Glance counterpart of `AgendulaTheme`: Material You on API 31+ when dynamic
 * colour is on, the brand fallback schemes otherwise. On [ThemeMode.SYSTEM] it
 * draws only from colour-role tokens, so light/dark follows the system without a
 * redraw; a forced light or dark theme pins one scheme for both.
 */
@Composable
fun AgendulaGlanceTheme(dynamicColor: Boolean, themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    val colors = when (themeMode) {
        ThemeMode.SYSTEM -> if (dynamic) GlanceTheme.colors else AgendulaGlanceColors
        ThemeMode.LIGHT -> ColorProviders(
            if (dynamic) dynamicLightColorScheme(context) else AgendulaLightFallback,
        )
        ThemeMode.DARK -> ColorProviders(
            if (dynamic) dynamicDarkColorScheme(context) else AgendulaDarkFallback,
        )
    }
    GlanceTheme(colors = colors, content = content)
}

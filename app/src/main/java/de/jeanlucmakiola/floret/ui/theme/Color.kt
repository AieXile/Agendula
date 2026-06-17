package de.jeanlucmakiola.floret.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Seed color anchoring the entire palette. A warm mauve — sibling to
 * Calendula's cool slate ([0xFF5C6B7A]) and deliberately distinct from
 * HouseHoldKeaper's sage. Evokes the marigold florets a Calendula head is
 * made of, kept muted to match the family's restrained aesthetic.
 *
 * Placeholder until branding lands; refine alongside the launcher icon.
 */
val FloretSeed: Color = Color(0xFF7A5C6B)

/**
 * Fallback light scheme for devices without dynamic color (API < 31) or when
 * the user disables it. Dynamic color (API 31+) is the real path; these are a
 * hand-picked approximation of the tonal palette generated from [FloretSeed].
 */
val FloretLightFallback = lightColorScheme(
    primary = Color(0xFF6A4E5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6D9E5),
    onPrimaryContainer = Color(0xFF2A101C),
    secondary = Color(0xFF705560),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFEFBFC),
    onBackground = Color(0xFF1F1A1C),
    surface = Color(0xFFFEFBFC),
    onSurface = Color(0xFF1F1A1C),
)

val FloretDarkFallback = darkColorScheme(
    primary = Color(0xFFE2BAC9),
    onPrimary = Color(0xFF402533),
    primaryContainer = Color(0xFF583B49),
    onPrimaryContainer = Color(0xFFF6D9E5),
    secondary = Color(0xFFD8BCC6),
    onSecondary = Color(0xFF3A2831),
    background = Color(0xFF161113),
    onBackground = Color(0xFFE9E0E3),
    surface = Color(0xFF161113),
    onSurface = Color(0xFFE9E0E3),
)

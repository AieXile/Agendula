package de.jeanlucmakiola.floret.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Soften a raw provider colour toward a pastel that fits the active theme —
 * keeps the hue (lists stay recognisable), caps saturation so harsh sync
 * colours stop screaming, pins brightness to read on light and dark. Copied
 * from Calendula so the families' colours behave identically.
 */
fun pastelize(rawArgb: Int, dark: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(rawArgb, hsv)
    hsv[1] = (hsv[1] * 0.6f).coerceIn(0.25f, 0.65f)
    hsv[2] = if (dark) 0.82f else 0.72f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Leading avatar for a task list: a neutral round chip holding a checklist glyph
 * tinted in the list's (pastelised) colour — the Calendula calendar-chip pattern,
 * with a task icon for Floret.
 */
@Composable
fun ListColorChip(color: Int, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Checklist,
            contentDescription = null,
            tint = pastelize(color, dark),
            modifier = Modifier.size(22.dp),
        )
    }
}

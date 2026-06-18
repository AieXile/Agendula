package de.jeanlucmakiola.floret.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * A small pill naming the task's list, filled in the list's (pastelised) colour.
 * The label flips black/white by the fill's luminance so it stays legible on any
 * hue in light and dark. Used in mixed views (smart lists) where rows span lists.
 */
@Composable
fun ListNameChip(name: String, color: Int, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val fill = pastelize(color, dark)
    val onFill = if (fill.luminance() > 0.5f) Color.Black else Color.White
    Surface(color = fill, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            text = name,
            color = onFill,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

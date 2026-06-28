package de.jeanlucmakiola.agendula.ui.common

import de.jeanlucmakiola.floret.components.pastelize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.domain.Priority

/**
 * Priority has no semantic Material role (M3 only ships `error` for danger), so
 * we give the ranked levels their own hues — green / amber / red. They're softened
 * harder than the list-colour [pastelize] (lower saturation, higher value) so the
 * chips stay gentle and low-key rather than shouting. [Priority.NONE] has no
 * colour (callers render it neutral).
 */
fun priorityFill(priority: Priority, dark: Boolean): Color {
    val base = when (priority) {
        Priority.HIGH -> 0xFFE53935.toInt()   // red
        Priority.MEDIUM -> 0xFFFFB300.toInt() // amber
        Priority.LOW -> 0xFF43A047.toInt()    // green
        Priority.NONE -> return Color.Unspecified
    }
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base, hsv)
    hsv[1] = if (dark) 0.36f else 0.22f // desaturate toward pastel
    hsv[2] = if (dark) 0.58f else 0.92f // light, soft fill in light; muted in dark
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Foreground tint (icon/text) for a priority level — the same red/amber/green as
 * [priorityFill], but kept saturated enough to read as a small glyph directly on
 * the surface rather than as a soft fill. Used by the task list's quiet meta line,
 * where priority is a tinted flag instead of a filled pill. [Priority.NONE] is
 * unspecified (callers render it neutral / omit it).
 */
fun priorityAccent(priority: Priority, dark: Boolean): Color {
    val base = when (priority) {
        Priority.HIGH -> 0xFFE53935.toInt()   // red
        Priority.MEDIUM -> 0xFFFB8C00.toInt() // orange-amber (darker than the fill for contrast)
        Priority.LOW -> 0xFF43A047.toInt()    // green
        Priority.NONE -> return Color.Unspecified
    }
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base, hsv)
    hsv[1] = if (dark) 0.55f else 0.85f
    hsv[2] = if (dark) 0.85f else 0.62f // light on dark surfaces; deep on light ones
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * A coloured status pill for a task's priority: a flag plus the level's [label],
 * filled in that level's pastel hue. Used read-only in the list and detail
 * screens (default [selected] = true), and as a selectable option in the edit
 * form — there [selected] = false shows the hue on the flag with an outlined,
 * unfilled container, and tapping commits the choice via [onClick].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriorityChip(
    priority: Priority,
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = true,
    showIcon: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val dark = isSystemInDarkTheme()
    val fill = priorityFill(priority, dark)
    val hasColor = fill.isSpecified

    val container: Color
    val content: Color
    val iconTint: Color
    var border: BorderStroke? = null
    when {
        selected && hasColor -> {
            container = fill
            content = if (fill.luminance() > 0.5f) Color.Black else Color.White
            iconTint = content
        }
        selected -> { // NONE, selected — neutral fill, no hue to show
            container = MaterialTheme.colorScheme.surfaceContainerHighest
            content = MaterialTheme.colorScheme.onSurfaceVariant
            iconTint = content
        }
        else -> { // an unselected option in a picker — outline, hue kept on the flag
            container = Color.Transparent
            content = MaterialTheme.colorScheme.onSurfaceVariant
            iconTint = if (hasColor) fill else MaterialTheme.colorScheme.onSurfaceVariant
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        }
    }

    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) {
                Icon(Icons.Rounded.Flag, contentDescription = null, tint = iconTint, modifier = Modifier.size(14.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
    val shape = RoundedCornerShape(50)
    if (onClick != null) {
        Surface(onClick = onClick, color = container, shape = shape, border = border, modifier = modifier) { body() }
    } else {
        Surface(color = container, shape = shape, border = border, modifier = modifier) { body() }
    }
}

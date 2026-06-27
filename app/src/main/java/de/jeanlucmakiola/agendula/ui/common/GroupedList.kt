package de.jeanlucmakiola.agendula.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Position of a row within a grouped list (the Android-15 settings pattern): a
 * run of rows shares one rounded container, full corners at the group's outer
 * edges and small corners between, separated by small gaps. Copied from
 * Calendula so the two apps read as one family.
 */
enum class Position { Top, Middle, Bottom, Alone }

fun positionOf(index: Int, count: Int): Position = when {
    count <= 1 -> Position.Alone
    index == 0 -> Position.Top
    index == count - 1 -> Position.Bottom
    else -> Position.Middle
}

/** Corner radii for a grouped segment: full at the group's outer edges, small between. */
fun groupedShape(position: Position, full: Dp, small: Dp): Shape = when (position) {
    Position.Alone -> RoundedCornerShape(full)
    Position.Top -> RoundedCornerShape(topStart = full, topEnd = full, bottomStart = small, bottomEnd = small)
    Position.Middle -> RoundedCornerShape(small)
    Position.Bottom -> RoundedCornerShape(topStart = small, topEnd = small, bottomStart = full, bottomEnd = full)
}

/**
 * One segment of a grouped list: a tonal [Surface] whose corner radii come from
 * [position] (so a run reads as a single rounded card), with a small gap below
 * all but the last. Corners morph rounder on press — the expressive shape play.
 * The caller supplies [content] and any horizontal inset via [modifier], so the
 * same primitive serves both the 16dp-inset lists and full-width detail cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupedSurface(
    position: Position,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val full by animateDpAsState(if (pressed) 36.dp else 22.dp, label = "fullCorner")
    val small by animateDpAsState(if (pressed) 36.dp else 6.dp, label = "smallCorner")
    val shape = groupedShape(position, full, small)
    val gap = when (position) {
        Position.Top, Position.Middle -> Modifier.padding(bottom = 2.dp)
        Position.Bottom, Position.Alone -> Modifier
    }
    val base = modifier.fillMaxWidth().then(gap)
    if (onClick != null) {
        Surface(onClick = onClick, color = color, shape = shape, interactionSource = interaction, modifier = base) { content() }
    } else {
        Surface(color = color, shape = shape, modifier = base) { content() }
    }
}

/**
 * One row in a grouped list: an M3 [ListItem] over a [GroupedSurface] (the
 * tonal, position-shaped, press-morphing family container). Insets 16dp like
 * the lists overview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupedRow(
    title: String,
    position: Position,
    modifier: Modifier = Modifier,
    summary: String? = null,
    selected: Boolean = false,
    minHeight: Dp = 72.dp,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val itemColors = if (selected) {
        ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
            leadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            supportingColor = MaterialTheme.colorScheme.onSecondaryContainer,
            trailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    } else {
        ListItemDefaults.colors(containerColor = Color.Transparent)
    }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    GroupedSurface(
        position = position,
        modifier = modifier.padding(horizontal = 16.dp),
        onClick = onClick,
        color = containerColor,
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { text -> { Text(text) } },
            leadingContent = leading,
            trailingContent = trailing,
            colors = itemColors,
            modifier = Modifier.heightIn(min = minHeight),
        )
    }
}

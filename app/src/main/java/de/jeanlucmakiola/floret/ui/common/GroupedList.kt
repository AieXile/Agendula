package de.jeanlucmakiola.floret.ui.common

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

/**
 * One row in a grouped list: an M3 [ListItem] over a tonal [Surface] whose
 * corner radii come from its [position] (so a run of rows reads as a single
 * rounded card). Corners morph further on press — the expressive shape play.
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val full by animateDpAsState(if (pressed) 36.dp else 22.dp, label = "fullCorner")
    val small by animateDpAsState(if (pressed) 36.dp else 6.dp, label = "smallCorner")
    val shape = when (position) {
        Position.Alone -> RoundedCornerShape(full)
        Position.Top -> RoundedCornerShape(
            topStart = full, topEnd = full, bottomStart = small, bottomEnd = small,
        )
        Position.Middle -> RoundedCornerShape(small)
        Position.Bottom -> RoundedCornerShape(
            topStart = small, topEnd = small, bottomStart = full, bottomEnd = full,
        )
    }
    val gap = when (position) {
        Position.Top, Position.Middle -> Modifier.padding(bottom = 2.dp)
        Position.Bottom, Position.Alone -> Modifier
    }
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
    val item: @Composable () -> Unit = {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { text -> { Text(text) } },
            leadingContent = leading,
            trailingContent = trailing,
            colors = itemColors,
            modifier = Modifier.heightIn(min = minHeight),
        )
    }
    val base = modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .then(gap)
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            color = containerColor,
            shape = shape,
            interactionSource = interaction,
            modifier = base,
        ) { item() }
    } else {
        Surface(color = containerColor, shape = shape, modifier = base) { item() }
    }
}

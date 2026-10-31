package com.aiexile.agendula.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A pill naming one tag. Solid secondary by default so it reads against any card;
 * [muted] is the quieter tonal look for something not yet applied, like a suggestion.
 */
@Composable
fun TagChip(
    name: String,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val container = if (muted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.secondary
    val content = if (muted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSecondary
    val shape = RoundedCornerShape(50)
    val body: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 12.dp, end = if (icon != null) 8.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (icon != null) {
                Icon(icon, contentDescription = iconContentDescription, modifier = Modifier.size(16.dp))
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, color = container, contentColor = content, shape = shape, modifier = modifier) { body() }
    } else {
        Surface(color = container, contentColor = content, shape = shape, modifier = modifier) { body() }
    }
}

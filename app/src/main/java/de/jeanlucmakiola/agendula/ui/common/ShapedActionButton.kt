package de.jeanlucmakiola.agendula.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon

/**
 * A top-bar action whose icon sits in a tonal container clipped to one of the
 * M3 Expressive [MaterialShapes] (cookie, clover, sunny, …) — Agendula's playful
 * take on a plain [androidx.compose.material3.IconButton]. Each action passes
 * its own [shape] and [containerColor], so a row of them reads as a set of
 * distinct little tokens rather than identical grey glyphs.
 *
 * Pressing springs the icon down a touch and gives it a small turn — a light
 * expressive flourish, no library motion APIs needed.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShapedActionButton(
    shape: RoundedPolygon,
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.tertiaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onTertiaryContainer,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, label = "actionScale")
    val rotation by animateFloatAsState(if (pressed) 24f else 0f, label = "actionRotation")

    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = shape.toShape(),
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(iconSize)
                    .scale(scale)
                    .rotate(rotation),
            )
        }
    }
}

/**
 * Named shapes for the app's top-bar actions, so each action is recognisably
 * "its own" and new actions just pick the next unused shape. Expose the
 * expressive [MaterialShapes] through a single opt-in site.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object ActionShapes {
    /** Settings — a 4-sided cookie (rounded, scalloped square). */
    val Settings: RoundedPolygon get() = MaterialShapes.Cookie4Sided
}

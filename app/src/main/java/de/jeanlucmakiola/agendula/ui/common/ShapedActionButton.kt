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
 * Pressing springs the whole shape down and gives it a turn — the scalloped
 * container is what dips and spins, while the glyph inside stays upright (a
 * spinning magnifier or list icon would just read as wrong). [spinIcon] opts a
 * glyph into turning too, for icons that read well mid-spin like the gear.
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
    spinIcon: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The shape (the scalloped cookie) turns and dips on press.
    val shapeRotation by animateFloatAsState(if (pressed) 40f else 0f, label = "shapeRotation")
    val scale by animateFloatAsState(if (pressed) 0.82f else 1f, label = "shapeScale")
    // The glyph's *net* turn: 0 keeps it upright, spinIcon gives it a quarter turn.
    val iconRotation by animateFloatAsState(
        if (pressed && spinIcon) 90f else 0f,
        label = "iconRotation",
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .scale(scale)
            .rotate(shapeRotation),
        shape = shape.toShape(),
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                // Counter the container's turn so the glyph's net rotation is just
                // [iconRotation] — upright by default, a quarter turn for the gear.
                modifier = Modifier
                    .size(iconSize)
                    .rotate(iconRotation - shapeRotation),
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

    /** Search — a 6-sided cookie, the same family as [Settings] but distinct. */
    val Search: RoundedPolygon get() = MaterialShapes.Cookie6Sided
}

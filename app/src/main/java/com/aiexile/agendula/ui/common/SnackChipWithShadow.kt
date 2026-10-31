package com.aiexile.agendula.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.floret.components.SnackChip

/** The pill's shape and the lift floret-kit's own [SnackChip] draws it with. */
private val ChipShape = RoundedCornerShape(50)
private val ChipElevation: Dp = 6.dp

/**
 * floret-kit's [SnackChip], with a shadow that actually leaves the pill.
 *
 * `SnackChip` puts `shadowElevation` on the `Surface` *inside* its
 * `AnimatedVisibility`, and that transition layer always clips to the chip's
 * bounds (`EnterExitTransition.createModifier` composes `graphicsLayer { clip = true }`).
 * The drop shadow is therefore cut into hard-edged wedges at the pill's ends
 * instead of falling away from it — visibly unlike the FAB it sits beside.
 *
 * The caller's [Modifier] lands *outside* that layer (`AnimatedVisibility` applies
 * it before the transition modifier), so the shadow is drawn here instead, and the
 * chip's own clipped copy is hidden by clipping to the identical pill. The lift
 * grows in with the chip rather than popping in ahead of it.
 *
 * ⚠️ A workaround around floret-kit, not a fork of its look: when `SnackChip` moves
 * its shadow outside its `AnimatedVisibility`, drop this wrapper and call it directly.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SnackChipWithShadow(
    visible: Boolean,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    maxWidth: Dp = Dp.Unspecified,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    // Starts at 0 even on first composition, so a chip that appears with its
    // receipt already showing does not flash a shadow before the pill is there.
    val elevation = remember { Animatable(0.dp, Dp.VectorConverter) }
    LaunchedEffect(visible) {
        elevation.animateTo(if (visible) ChipElevation else 0.dp, spec)
    }
    val lift by elevation.asState()
    SnackChip(
        visible = visible,
        message = message,
        modifier = modifier
            .shadow(lift, ChipShape, clip = false)
            .clip(ChipShape),
        actionLabel = actionLabel,
        onAction = onAction,
        maxWidth = maxWidth,
        color = color,
    )
}

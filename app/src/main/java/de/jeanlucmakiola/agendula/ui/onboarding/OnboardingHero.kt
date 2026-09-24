package de.jeanlucmakiola.agendula.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Agendula's onboarding hero: an icon in the brand squircle. Each app keeps its
 * own hero; the surrounding shell comes from floret-kit's OnboardingScaffold.
 */
@Composable
internal fun SquircleHero(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(128.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(56.dp),
        )
    }
}

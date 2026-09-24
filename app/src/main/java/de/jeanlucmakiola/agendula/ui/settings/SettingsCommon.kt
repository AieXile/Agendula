package de.jeanlucmakiola.agendula.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.floret.components.GroupedListInset

/**
 * Pieces shared by the settings hub and its sub-screens. Each sub-screen owns
 * whatever only it uses; anything two of them need lives here.
 */

/**
 * Accent for a leading icon chip. The chips are a scanning aid, so no two rows
 * in one group share an accent; [Neutral] is a step back for reference rows
 * rather than a fourth colour to rotate through.
 */
internal enum class ChipAccent { Neutral, Primary, Secondary, Tertiary }

/** Leading circular icon chip, coloured by an M3 container/on-container pair. */
@Composable
internal fun CategoryIcon(icon: ImageVector, accent: ChipAccent) {
    val scheme = MaterialTheme.colorScheme
    val (background, iconColor) = when (accent) {
        ChipAccent.Neutral -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        ChipAccent.Primary -> scheme.primaryContainer to scheme.onPrimaryContainer
        ChipAccent.Secondary -> scheme.secondaryContainer to scheme.onSecondaryContainer
        ChipAccent.Tertiary -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Muted supporting text under a section header, matching the form-fields hint. */
@Composable
internal fun SettingsHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = GroupedListInset, vertical = 4.dp),
    )
}

/**
 * The app icon as a rounded chip: the launcher mark over its background colour,
 * oversized and clipped the way a launcher mask would. Sized just past the two
 * text lines beside it; [MARK_OVERSCAN] holds the crop as it shrinks.
 */
@Composable
internal fun AppLogo() {
    Box(
        modifier = Modifier
            .size(LOGO_SIZE)
            .clip(RoundedCornerShape(16.dp))
            .background(colorResource(R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = stringResource(R.string.settings_about_logo_desc),
            modifier = Modifier.requiredSize(LOGO_SIZE * MARK_OVERSCAN),
        )
    }
}

/** Roughly the height of the app name and author lines it sits beside. */
private val LOGO_SIZE = 56.dp

/**
 * How far the mark overruns its chip. An adaptive icon's foreground carries a
 * wide safe margin, so at 1:1 it would sit small and lost; launchers crop it too.
 */
private const val MARK_OVERSCAN = 1.5f

/** Plain centred version mark at the foot of the settings list (no card). */
@Composable
internal fun AppVersionText() {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "—"
    }
    Text(
        text = stringResource(R.string.settings_about_version, versionName),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    )
}

internal fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    runCatching { context.startActivity(intent) }
}

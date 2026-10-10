package de.jeanlucmakiola.agendula.ui.accounts.add

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.ui.accounts.ProviderLogo
import de.jeanlucmakiola.caldav.CalDavProvider
import de.jeanlucmakiola.caldav.ServerQuirk
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.components.positionOf

/**
 * Step 1: which service the tasks live on.
 *
 * The services we know by name, each by its own mark, and then the escape hatch
 * for everything else. Choosing here is what lets step 2 ask one clear question
 * instead of "an email address, or a server address, whichever you have" — and
 * it is what puts a provider's app-password rule in front of the user *before*
 * the 401 rather than after it.
 */
@Composable
internal fun ProviderStep(step: AddAccountStep.ChooseProvider, viewModel: AddAccountViewModel) {
    val services = CalDavProvider.selectable
    services.forEachIndexed { index, provider ->
        val choice = ProviderChoice.Service(provider)
        val chosen = step.choice == choice
        // ⚠️ On the row, not behind a tap. Google is listed because people come
        // looking for it — an absent row reads as the app being unfinished
        // rather than as Google's own limitation — but a row that only reveals
        // why it cannot be used *after* being selected is the worst of both: it
        // looks available, then quietly refuses.
        val unusable = ServerQuirk.forProvider(provider)?.isFatal == true
        GroupedRow(
            title = provider.label,
            summary = when {
                unusable -> stringResource(R.string.add_account_provider_unsupported)
                else -> provider.primaryDomain
                    ?: stringResource(R.string.add_account_provider_self_hosted)
            },
            position = positionOf(index, services.size),
            selected = chosen && !unusable,
            dimmed = unusable,
            leading = { ProviderLogo(provider) },
            trailing = { if (chosen && !unusable) SelectedCheck() },
            // Still tappable: the summary is the short reason, and the note the
            // tap brings up is the long one.
            onClick = { viewModel.onProviderChosen(choice) },
        )
    }

    GroupedSectionHeader(stringResource(R.string.add_account_provider_other_header))
    val other = ProviderChoice.OtherServer
    val otherChosen = step.choice == other
    GroupedRow(
        title = stringResource(R.string.add_account_provider_other),
        summary = stringResource(R.string.add_account_provider_other_summary),
        position = Position.Alone,
        selected = otherChosen,
        // Null is the family's "a server, unnamed" mark — exactly what this row
        // is choosing.
        leading = { ProviderLogo(provider = null) },
        trailing = { if (otherChosen) SelectedCheck() },
        onClick = { viewModel.onProviderChosen(other) },
    )
    Spacer(Modifier.height(16.dp))
}

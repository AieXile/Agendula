package de.jeanlucmakiola.agendula.ui.accounts.add

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.caldav.ServerQuirk
import de.jeanlucmakiola.floret.components.InstructionSteps

/**
 * Step 2, first half: the errand the chosen service requires, before anything is
 * typed.
 *
 * A screen of its own rather than a note under the address field. What it asks
 * for happens somewhere else entirely — open a browser, sign in to the provider,
 * mint an app password, come back — so it has to be read *before* the fields it
 * makes answerable, and it needs the room to be four numbered actions rather
 * than a paragraph. Underneath an input, it was neither.
 *
 * The list carries no header of its own: the step's headline already names the
 * requirement, and repeating it above the rows says it twice.
 */
@Composable
internal fun SetupStep(step: AddAccountStep.PrepareAccess) {
    InstructionSteps(
        steps = stringArrayResource(step.quirk.steps).asList(),
        footnote = stringResource(step.quirk.footnote),
    )
    Spacer(Modifier.height(16.dp))
}

/** The headline for the errand — what the service requires, in one line. */
internal val ServerQuirk.setupTitle: Int
    get() = when (this) {
        ServerQuirk.FASTMAIL_APP_PASSWORD -> R.string.add_account_setup_fastmail_title
        else -> R.string.add_account_setup_icloud_title
    }

private val ServerQuirk.steps: Int
    get() = when (this) {
        ServerQuirk.FASTMAIL_APP_PASSWORD -> R.array.add_account_setup_fastmail_steps
        // Only two quirks reach this screen — ServerQuirk.hasSetupSteps is what
        // decides — so the branch that cannot happen takes the other one rather
        // than inventing a third set of instructions to be wrong with.
        else -> R.array.add_account_setup_icloud_steps
    }

private val ServerQuirk.footnote: Int
    get() = when (this) {
        ServerQuirk.FASTMAIL_APP_PASSWORD -> R.string.add_account_setup_fastmail_footnote
        else -> R.string.add_account_setup_icloud_footnote
    }

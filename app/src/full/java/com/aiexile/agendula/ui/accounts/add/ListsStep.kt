package com.aiexile.agendula.ui.accounts.add

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiexile.agendula.R
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.components.positionOf

/** Step 4: which of the account's collections to keep in sync. */
@Composable
internal fun ListsStep(step: AddAccountStep.ChooseLists, viewModel: AddAccountViewModel) {
    // The heading is the shell's; this step only lists.
    step.collections.forEachIndexed { index, collection ->
        val checked = collection.url in step.selected
        GroupedRow(
            title = collection.displayName ?: collection.url.encodedPath,
            summary = when {
                collection.readOnly -> stringResource(R.string.add_account_lists_read_only)
                collection.isShared -> stringResource(R.string.add_account_lists_shared)
                else -> null
            },
            position = positionOf(index, step.collections.size),
            selected = checked,
            // The family marks a chosen row with a check, not a Material
            // checkbox — same affordance every picker in the app uses.
            trailing = { if (checked) SelectedCheck() },
            onClick = { viewModel.onListToggled(collection.url) },
        )
    }
    Spacer(Modifier.height(16.dp))
}

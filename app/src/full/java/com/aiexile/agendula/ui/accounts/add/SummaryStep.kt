package com.aiexile.agendula.ui.accounts.add

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.aiexile.agendula.R
import com.aiexile.agendula.ui.accounts.ProviderLogo
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.positionOf

/**
 * The receipt: what the flow just set up, under the name the accounts list will
 * use for it.
 *
 * Not a confirmation — the account is already saved, and there is nothing here
 * to undo. It exists because the wizard used to vanish on success, leaving "did
 * it take the right lists, and under which name?" answerable only by going and
 * finding the account again.
 */
@Composable
internal fun SummaryStep(step: AddAccountStep.Summary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderLogo(step.provider, size = 48.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(step.title, style = MaterialTheme.typography.titleMedium)
            // The account's own two supporting facts, in the order the accounts
            // list gives them: what it is, then who you are on it.
            listOfNotNull(step.secondary, step.username.takeIf { it.isNotBlank() })
                .forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }

    Spacer(Modifier.height(24.dp))

    GroupedSectionHeader(
        pluralStringResource(
            R.plurals.add_account_summary_lists,
            step.lists.size,
            step.lists.size,
        ),
    )
    step.lists.forEachIndexed { index, name ->
        GroupedRow(
            title = name,
            position = positionOf(index, step.lists.size),
            leading = {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }
    Spacer(Modifier.height(16.dp))
}

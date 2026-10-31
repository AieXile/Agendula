package com.aiexile.agendula.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiexile.agendula.R
import com.aiexile.agendula.domain.recurrence.RecurringScope
import de.jeanlucmakiola.floret.components.OptionCard

/**
 * How far a write to a recurring task reaches: this occurrence, it and
 * everything after, or the whole series. One of the two carve-outs from the
 * full-screen picker rule.
 *
 * [allowOccurrence], [allowFollowing] and [allowSeries] drop options that do
 * not apply; [reason] then says why.
 */
@Composable
fun RecurringScopeDialog(
    title: String,
    onSelect: (RecurringScope) -> Unit,
    onDismiss: () -> Unit,
    allowOccurrence: Boolean = true,
    allowFollowing: Boolean = true,
    allowSeries: Boolean = true,
    reason: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (reason != null) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (allowOccurrence) {
                    OptionCard(
                        label = stringResource(R.string.recurring_scope_occurrence),
                        onClick = { onSelect(RecurringScope.ThisOccurrence) },
                    )
                }
                if (allowFollowing) {
                    OptionCard(
                        label = stringResource(R.string.recurring_scope_following),
                        onClick = { onSelect(RecurringScope.ThisAndFollowing) },
                    )
                }
                if (allowSeries) {
                    OptionCard(
                        label = stringResource(R.string.recurring_scope_series),
                        onClick = { onSelect(RecurringScope.AllOccurrences) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

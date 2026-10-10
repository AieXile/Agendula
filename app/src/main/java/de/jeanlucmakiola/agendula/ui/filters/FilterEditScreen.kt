package de.jeanlucmakiola.agendula.ui.filters

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.domain.DueWindow
import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.SavedFilter
import de.jeanlucmakiola.agendula.domain.TagMatch
import de.jeanlucmakiola.agendula.ui.common.ListColorChip
import de.jeanlucmakiola.agendula.ui.common.NameField
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.OptionPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.components.positionOf

/**
 * Creates or edits a [SavedFilter]: a name, then due windows, priority, lists and
 * tags. Each multi-choice section leads with an "any" row that is selected while
 * nothing in it is, so an untouched section visibly doesn't narrow.
 */
@Composable
fun FilterEditScreen(
    viewModel: FilterEditViewModel,
    isNew: Boolean,
    onSaved: (Long) -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val tagsInUse by viewModel.tags.collectAsStateWithLifecycle()
    val filter = draft
    var choosingPriority by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val save = { viewModel.save(onSaved) }

    CollapsingScaffold(
        title = stringResource(if (isNew) R.string.filter_new_title else R.string.filter_edit_title),
        onBack = onBack,
        largeTopBar = false,
        actions = {
            Button(
                onClick = save,
                enabled = filter?.name?.isNotBlank() == true,
                modifier = Modifier.padding(end = 12.dp),
            ) { Text(stringResource(R.string.save)) }
        },
    ) {
        if (filter == null) return@CollapsingScaffold

        NameField(
            name = filter.name,
            placeholder = stringResource(R.string.filter_name_hint),
            autoFocus = isNew,
            onNameChange = { name -> viewModel.update { it.copy(name = name) } },
            onImeAction = save,
        )

        GroupedSectionHeader(stringResource(R.string.filter_section_due))
        val windows = DueWindow.entries
        ChoiceRow(
            title = stringResource(R.string.filter_any_due),
            position = positionOf(0, windows.size + 1),
            selected = filter.due.isEmpty(),
            onClick = { viewModel.update { it.copy(due = emptySet()) } },
        )
        windows.forEachIndexed { index, window ->
            ChoiceRow(
                title = dueWindowLabel(window),
                position = positionOf(index + 1, windows.size + 1),
                selected = window in filter.due,
                onClick = { viewModel.update { it.copy(due = it.due.toggle(window)) } },
            )
        }

        GroupedSectionHeader(stringResource(R.string.filter_section_priority))
        GroupedRow(
            title = minPriorityLabel(filter.minPriority),
            position = Position.Alone,
            trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
            onClick = { choosingPriority = true },
        )

        if (lists.isNotEmpty()) {
            GroupedSectionHeader(stringResource(R.string.filter_section_lists))
            val showAccount = lists.mapTo(HashSet()) { it.accountName }.size > 1
            ChoiceRow(
                title = stringResource(R.string.filter_any_list),
                position = positionOf(0, lists.size + 1),
                selected = filter.anyList,
                onClick = { viewModel.update { it.copy(listIds = emptySet(), listsElsewhere = false) } },
            )
            lists.forEachIndexed { index, list ->
                ChoiceRow(
                    title = list.name,
                    summary = list.accountName.takeIf { showAccount },
                    position = positionOf(index + 1, lists.size + 1),
                    selected = list.id in filter.listIds,
                    leading = { ListColorChip(list.color) },
                    onClick = { viewModel.update { it.copy(listIds = it.listIds.toggle(list.id)) } },
                )
            }
        }

        // A tag no open task carries any more stays listed while the filter uses
        // it, so it can still be unticked.
        val tags = remember(tagsInUse, filter.tags) {
            (tagsInUse + filter.tags).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
        if (tags.isNotEmpty()) {
            GroupedSectionHeader(stringResource(R.string.filter_section_tags))
            ChoiceRow(
                title = stringResource(R.string.filter_any_tag),
                position = positionOf(0, tags.size + 1),
                selected = filter.tags.isEmpty(),
                onClick = { viewModel.update { it.copy(tags = emptySet()) } },
            )
            tags.forEachIndexed { index, tag ->
                ChoiceRow(
                    title = tag,
                    position = positionOf(index + 1, tags.size + 1),
                    selected = tag in filter.tags,
                    onClick = { viewModel.update { it.copy(tags = it.tags.toggle(tag)) } },
                )
            }
            if (filter.tags.size > 1) {
                Spacer(Modifier.height(8.dp))
                GroupedRow(
                    title = stringResource(R.string.filter_match_all_tags),
                    summary = stringResource(
                        if (filter.tagMatch == TagMatch.ALL) R.string.filter_match_all_on else R.string.filter_match_all_off,
                    ),
                    position = Position.Alone,
                    trailing = {
                        Switch(
                            checked = filter.tagMatch == TagMatch.ALL,
                            onCheckedChange = { all ->
                                viewModel.update { it.copy(tagMatch = if (all) TagMatch.ALL else TagMatch.ANY) }
                            },
                        )
                    },
                    onClick = {
                        viewModel.update { it.copy(tagMatch = if (it.tagMatch == TagMatch.ALL) TagMatch.ANY else TagMatch.ALL) }
                    },
                )
            }
        }

        if (filter.id > 0) {
            Spacer(Modifier.height(24.dp))
            GroupedRow(
                title = stringResource(R.string.filter_delete),
                position = Position.Alone,
                contentColor = MaterialTheme.colorScheme.error,
                leading = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                onClick = { confirmDelete = true },
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (choosingPriority && filter != null) {
        OptionPicker(
            title = stringResource(R.string.filter_section_priority),
            options = Priority.entries,
            selected = filter.minPriority,
            label = { minPriorityLabel(it) },
            onSelect = { priority -> viewModel.update { it.copy(minPriority = priority) } },
            onDismiss = { choosingPriority = false },
        )
    }

    if (confirmDelete && filter != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.filter_delete_title, filter.name)) },
            text = { Text(stringResource(R.string.filter_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete(onDeleted) }) {
                    Text(stringResource(R.string.filter_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    position: Position,
    selected: Boolean,
    onClick: () -> Unit,
    summary: String? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    GroupedRow(
        title = title,
        summary = summary,
        position = position,
        selected = selected,
        leading = leading,
        trailing = if (selected) {
            { SelectedCheck() }
        } else {
            null
        },
        onClick = onClick,
    )
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

@Composable
private fun dueWindowLabel(window: DueWindow): String = stringResource(
    when (window) {
        DueWindow.OVERDUE -> R.string.smart_overdue
        DueWindow.TODAY -> R.string.smart_today
        DueWindow.UPCOMING -> R.string.smart_upcoming
        DueWindow.NO_DATE -> R.string.smart_no_date
    },
)

@Composable
internal fun minPriorityLabel(priority: Priority): String = stringResource(
    when (priority) {
        Priority.NONE -> R.string.filter_priority_any
        Priority.LOW -> R.string.filter_priority_low
        Priority.MEDIUM -> R.string.filter_priority_medium
        Priority.HIGH -> R.string.filter_priority_high
    },
)

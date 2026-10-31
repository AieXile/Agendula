package com.aiexile.agendula.ui.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiexile.agendula.R
import com.aiexile.agendula.ui.common.accountLabel
import com.aiexile.agendula.ui.common.icon
import com.aiexile.agendula.ui.common.labelRes
import com.aiexile.agendula.domain.TaskFormField
import com.aiexile.agendula.domain.TaskList
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.components.pastelize
import de.jeanlucmakiola.floret.components.positionOf

/** What a new task's edit form starts out as: its fields, its list, and the add affordances. */
@Composable
internal fun TaskFormScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    visible: Boolean,
) {
    var showDefaultList by remember { mutableStateOf(false) }

    CollapsingScaffold(
        title = stringResource(R.string.settings_section_task_form),
        onBack = onBack,
        predictiveBack = true,
        visible = visible,
    ) {
        SettingsHint(stringResource(R.string.settings_form_fields_hint))
        Spacer(Modifier.height(8.dp))
        val fields = TaskFormField.entries
        fields.forEachIndexed { index, field ->
            val checked = field in state.settings.defaultEditFields
            GroupedRow(
                title = stringResource(formFieldLabel(field)),
                position = positionOf(index, fields.size),
                leading = {
                    Icon(
                        imageVector = formFieldIcon(field),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailing = {
                    Switch(checked = checked, onCheckedChange = { viewModel.setFormFieldDefault(field, it) })
                },
                onClick = { viewModel.setFormFieldDefault(field, !checked) },
            )
        }

        Spacer(Modifier.height(24.dp))
        GroupedRow(
            title = stringResource(R.string.settings_default_list),
            summary = defaultListLabel(state),
            position = Position.Top,
            onClick = { showDefaultList = true },
        )
        GroupedRow(
            title = stringResource(R.string.settings_autofocus_title),
            summary = stringResource(R.string.settings_autofocus_title_hint),
            position = Position.Middle,
            trailing = {
                Switch(
                    checked = state.settings.autofocusTitle,
                    onCheckedChange = viewModel::setAutofocusTitle,
                )
            },
            onClick = { viewModel.setAutofocusTitle(!state.settings.autofocusTitle) },
        )
        GroupedRow(
            title = stringResource(R.string.settings_add_subtask_row),
            summary = stringResource(R.string.settings_add_subtask_row_hint),
            position = Position.Middle,
            trailing = {
                Switch(
                    checked = state.settings.showAddSubtaskRow,
                    onCheckedChange = viewModel::setShowAddSubtaskRow,
                )
            },
            onClick = { viewModel.setShowAddSubtaskRow(!state.settings.showAddSubtaskRow) },
        )
        GroupedRow(
            title = stringResource(R.string.settings_default_start_today),
            summary = stringResource(R.string.settings_default_start_today_hint),
            position = Position.Middle,
            trailing = {
                Switch(
                    checked = state.settings.defaultStartToday,
                    onCheckedChange = viewModel::setDefaultStartToday,
                )
            },
            onClick = { viewModel.setDefaultStartToday(!state.settings.defaultStartToday) },
        )
        GroupedRow(
            title = stringResource(R.string.settings_bottom_add_bar),
            summary = stringResource(R.string.settings_bottom_add_bar_hint),
            position = Position.Bottom,
            trailing = {
                Switch(
                    checked = state.settings.bottomAddBar,
                    onCheckedChange = viewModel::setBottomAddBar,
                )
            },
            onClick = { viewModel.setBottomAddBar(!state.settings.bottomAddBar) },
        )
    }

    if (showDefaultList) {
        DefaultListPicker(
            lists = state.lists,
            selectedId = state.settings.defaultListId,
            onSelect = { viewModel.setDefaultList(it); showDefaultList = false },
            onDismiss = { showDefaultList = false },
        )
    }
}

/**
 * "First available" above the lists a new task can go into, grouped under their
 * account like the editor's list picker. Read-only lists are left out.
 */
@Composable
private fun DefaultListPicker(
    lists: List<TaskList>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val groups = remember(lists) {
        lists.filter { it.id > 0L && it.acceptsWrites }.groupBy { it.accountName }
    }
    FullScreenPicker(title = stringResource(R.string.settings_default_list), onDismiss = onDismiss) {
        val firstSelected = groups.values.none { inAccount -> inAccount.any { it.id == selectedId } }
        GroupedRow(
            title = stringResource(R.string.settings_default_list_first),
            position = Position.Alone,
            selected = firstSelected,
            trailing = if (firstSelected) ({ SelectedCheck() }) else null,
            onClick = { onSelect(null) },
        )
        groups.forEach { (account, inAccount) ->
            Spacer(Modifier.height(8.dp))
            if (account.isNotBlank()) {
                GroupedSectionHeader(accountLabel(account, inAccount.first().isLocal))
            }
            inAccount.forEachIndexed { index, list ->
                val selected = list.id == selectedId
                GroupedRow(
                    title = list.name,
                    position = positionOf(index, inAccount.size),
                    selected = selected,
                    leading = { Icon(Icons.Rounded.Circle, contentDescription = null, tint = pastelize(list.color, dark)) },
                    trailing = if (selected) ({ SelectedCheck() }) else null,
                    onClick = { onSelect(list.id) },
                )
            }
        }
    }
}

@Composable
private fun defaultListLabel(state: SettingsUiState): String =
    state.lists.firstOrNull { it.id == state.settings.defaultListId && it.acceptsWrites }?.name
        ?: stringResource(R.string.settings_default_list_first)

private fun formFieldLabel(field: TaskFormField): Int = field.labelRes()

private fun formFieldIcon(field: TaskFormField): ImageVector = field.icon()

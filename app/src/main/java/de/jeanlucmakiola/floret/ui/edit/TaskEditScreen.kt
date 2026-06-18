package de.jeanlucmakiola.floret.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.floret.R
import de.jeanlucmakiola.floret.domain.Priority
import de.jeanlucmakiola.floret.domain.TaskFormError
import de.jeanlucmakiola.floret.ui.common.DateTimeField
import de.jeanlucmakiola.floret.ui.tasklist.priorityLabel

/**
 * Create / edit form. Title, description, list, start / due date-time with an
 * all-day toggle, priority and a reminder offset — validated through the M1
 * [TaskEditViewModel] / [de.jeanlucmakiola.floret.domain.TaskForm]. The VM flips
 * `saved` once the write lands, which pops us back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.new_task_title else R.string.edit_task_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::save, enabled = !state.loading) {
                        Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.save))
                    }
                },
            )
        },
    ) { inner ->
        if (state.loading) return@Scaffold

        Column(
            modifier = Modifier
                .padding(inner)
                // Shrink the scroll viewport by the keyboard so the focused
                // field scrolls into view above the IME instead of being hidden.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text(stringResource(R.string.field_title)) },
                singleLine = true,
                isError = TaskFormError.BLANK_TITLE in state.errors,
                supportingText = errorText(TaskFormError.BLANK_TITLE in state.errors, R.string.error_blank_title),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text(stringResource(R.string.field_description)) },
                modifier = Modifier.fillMaxWidth(),
            )

            ListPicker(
                lists = state.lists,
                selectedId = state.listId,
                isError = TaskFormError.NO_LIST in state.errors,
                onSelect = viewModel::onListChange,
            )

            // All-day toggle
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.edit_all_day), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.isAllDay, onCheckedChange = viewModel::onAllDayChange)
            }

            DateTimeField(
                label = stringResource(R.string.edit_start_label),
                value = state.start,
                allDay = state.isAllDay,
                onChange = viewModel::onStartChange,
            )
            DateTimeField(
                label = stringResource(R.string.edit_due_label),
                value = state.due,
                allDay = state.isAllDay,
                onChange = viewModel::onDueChange,
            )
            if (TaskFormError.DUE_BEFORE_START in state.errors) {
                FieldError(R.string.error_due_before_start)
            }

            // Priority
            Column {
                Text(
                    stringResource(R.string.edit_priority_label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    Priority.entries.forEachIndexed { index, priority ->
                        SegmentedButton(
                            selected = state.priority == priority,
                            onClick = { viewModel.onPriorityChange(priority) },
                            shape = SegmentedButtonDefaults.itemShape(index, Priority.entries.size),
                        ) { Text(priorityLabel(priority)) }
                    }
                }
            }

            ReminderPicker(
                minutes = state.reminderMinutesBeforeDue,
                onSelect = viewModel::onReminderChange,
            )
            if (TaskFormError.REMINDER_WITHOUT_DUE in state.errors) {
                FieldError(R.string.error_reminder_without_due)
            }

            if (state.saveFailed) FieldError(R.string.edit_save_failed)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPicker(
    lists: List<de.jeanlucmakiola.floret.domain.TaskList>,
    selectedId: Long?,
    isError: Boolean,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = lists.firstOrNull { it.id == selectedId }?.name.orEmpty()

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.edit_list_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = isError,
            supportingText = errorText(isError, R.string.error_no_list),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            lists.forEach { list ->
                DropdownMenuItem(
                    text = { Text(list.name) },
                    onClick = {
                        onSelect(list.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

private data class ReminderOption(val labelRes: Int, val minutes: Int?)

private val reminderOptions = listOf(
    ReminderOption(R.string.reminder_none, null),
    ReminderOption(R.string.reminder_at_due, 0),
    ReminderOption(R.string.reminder_5_min, 5),
    ReminderOption(R.string.reminder_10_min, 10),
    ReminderOption(R.string.reminder_30_min, 30),
    ReminderOption(R.string.reminder_1_hour, 60),
    ReminderOption(R.string.reminder_1_day, 1_440),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderPicker(minutes: Int?, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = reminderOptions.firstOrNull { it.minutes == minutes } ?: reminderOptions.first()

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(current.labelRes),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.edit_reminder_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            reminderOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        onSelect(option.minutes)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A supporting-text lambda that shows [messageRes] only when [show]. */
@Composable
private fun errorText(show: Boolean, messageRes: Int): (@Composable () -> Unit)? =
    if (show) {
        { Text(stringResource(messageRes)) }
    } else {
        null
    }

@Composable
private fun FieldError(messageRes: Int) {
    Text(
        text = stringResource(messageRes),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 4.dp),
    )
}

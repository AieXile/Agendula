package de.jeanlucmakiola.floret.ui.edit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.floret.R
import de.jeanlucmakiola.floret.domain.Priority
import de.jeanlucmakiola.floret.domain.TaskFormError
import de.jeanlucmakiola.floret.domain.TaskFormField
import de.jeanlucmakiola.floret.domain.TaskList
import de.jeanlucmakiola.floret.ui.common.InlineTextField
import de.jeanlucmakiola.floret.ui.common.OptionCard
import de.jeanlucmakiola.floret.ui.common.formatDate
import de.jeanlucmakiola.floret.ui.common.formatTime
import de.jeanlucmakiola.floret.ui.common.localToInstant
import de.jeanlucmakiola.floret.ui.common.pastelize
import de.jeanlucmakiola.floret.ui.common.toLocalDate
import de.jeanlucmakiola.floret.ui.common.toLocalTime
import de.jeanlucmakiola.floret.ui.tasklist.priorityLabel
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.time.Instant

/**
 * Create / edit form, in the family's tonal-card language (mirrors Calendula's
 * event editor): a borderless headline title with a list-colour accent bar, a
 * "When" card with the all-day toggle and tappable schedule rows, a tappable
 * list card, and the optional Description / Priority / Reminder sections that
 * unfold from "More fields" (which ones start open is a setting). Validated
 * through the M1 [TaskEditViewModel] / [de.jeanlucmakiola.floret.domain.TaskForm];
 * the VM flips `saved` once the write lands, which pops us back.
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
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    Button(
                        onClick = viewModel::save,
                        enabled = !state.loading,
                        modifier = Modifier.padding(end = 12.dp),
                    ) { Text(stringResource(R.string.save)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { inner ->
        if (state.loading) return@Scaffold
        EditContent(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.padding(inner),
        )
    }
}

private enum class PickerTarget { Start, Due }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditContent(
    state: TaskEditUiState,
    viewModel: TaskEditViewModel,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val selectedList = state.lists.firstOrNull { it.id == state.listId }
    // The accent ties the form to the detail screen's language: the bar under
    // the title takes the target list's colour.
    val accent = selectedList?.let { pastelize(it.color, dark) } ?: MaterialTheme.colorScheme.primary
    val gap = 12.dp

    var pickerTarget by remember { mutableStateOf<PickerTarget?>(null) }
    var showListPicker by rememberSaveable { mutableStateOf(false) }
    var showReminderPicker by rememberSaveable { mutableStateOf(false) }
    var showFieldPicker by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            // Shrink the scroll viewport by the keyboard so the focused field
            // scrolls into view above the IME instead of being hidden.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 40.dp),
    ) {
        // Title: borderless headline + accent bar, mirroring the detail screen.
        InlineField(
            value = state.title,
            onValueChange = viewModel::onTitleChange,
            placeholder = stringResource(R.string.edit_title_hint),
            textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(3.dp)
                .background(accent, RoundedCornerShape(2.dp)),
        )
        if (TaskFormError.BLANK_TITLE in state.errors) {
            Spacer(Modifier.height(6.dp))
            FieldError(R.string.error_blank_title)
        }

        Spacer(Modifier.height(20.dp))

        // "When" card: the icon centres on the all-day row (header); the start /
        // due rows continue below in the same text column.
        EditCard(
            icon = Icons.Rounded.Schedule,
            iconContentDescription = stringResource(R.string.edit_when),
            header = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.edit_all_day),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = state.isAllDay, onCheckedChange = viewModel::onAllDayChange)
                }
            },
        ) {
            Spacer(Modifier.height(4.dp))
            ScheduleRow(
                label = stringResource(R.string.edit_start_label),
                value = state.start,
                allDay = state.isAllDay,
                onPick = { pickerTarget = PickerTarget.Start },
                onClear = { viewModel.onStartChange(null) },
            )
            ScheduleRow(
                label = stringResource(R.string.edit_due_label),
                value = state.due,
                allDay = state.isAllDay,
                onPick = { pickerTarget = PickerTarget.Due },
                onClear = { viewModel.onDueChange(null) },
                isError = TaskFormError.DUE_BEFORE_START in state.errors,
            )
            if (TaskFormError.DUE_BEFORE_START in state.errors) {
                Spacer(Modifier.height(2.dp))
                FieldError(R.string.error_due_before_start)
            }
        }

        Spacer(Modifier.height(gap))

        // List card — tap anywhere to pick the target list.
        EditCard(
            icon = Icons.Rounded.Checklist,
            iconContentDescription = stringResource(R.string.edit_list_label),
            iconTint = accent,
            onClick = { showListPicker = true }.takeIf { state.lists.isNotEmpty() },
        ) {
            Text(
                text = selectedList?.name ?: stringResource(R.string.error_no_list),
                style = MaterialTheme.typography.titleMedium,
                color = if (selectedList == null) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            selectedList?.accountName?.takeIf { it.isNotBlank() }?.let { account ->
                Text(
                    text = account,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Optional sections: which start open is a setting; the rest unfold
        // behind the "More fields" button below.
        OptionalFormSection(visible = TaskFormField.Description in state.visibleFields) {
            Spacer(Modifier.height(gap))
            EditCard(
                icon = Icons.AutoMirrored.Rounded.Notes,
                iconContentDescription = stringResource(R.string.field_description),
                iconAtTop = true,
            ) {
                InlineField(
                    value = state.description,
                    onValueChange = viewModel::onDescriptionChange,
                    placeholder = stringResource(R.string.field_description),
                    singleLine = false,
                    minLines = 3,
                )
            }
        }

        OptionalFormSection(visible = TaskFormField.Priority in state.visibleFields) {
            Spacer(Modifier.height(gap))
            // Not an EditCard: the four buttons need the card's full width, so
            // the label sits on its own row above rather than in the indented
            // icon-gutter column (where "Medium" would wrap to a second line).
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Flag,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.edit_priority_label),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        Priority.entries.forEachIndexed { index, priority ->
                            SegmentedButton(
                                selected = state.priority == priority,
                                onClick = { viewModel.onPriorityChange(priority) },
                                shape = SegmentedButtonDefaults.itemShape(index, Priority.entries.size),
                                // Drop the selected-check icon; with four buttons
                                // its reserved width is what crowds the labels.
                                icon = {},
                            ) {
                                Text(
                                    text = priorityLabel(priority),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            }
        }

        OptionalFormSection(visible = TaskFormField.Reminder in state.visibleFields) {
            Spacer(Modifier.height(gap))
            EditCard(
                icon = Icons.Rounded.Notifications,
                iconContentDescription = null,
                onClick = { showReminderPicker = true },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(reminderOptionFor(state.reminderMinutesBeforeDue).labelRes),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.edit_reminder_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (TaskFormError.REMINDER_WITHOUT_DUE in state.errors) {
                    Spacer(Modifier.height(2.dp))
                    FieldError(R.string.error_reminder_without_due)
                }
            }
        }

        OptionalFormSection(visible = state.hiddenFields.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            TextButton(
                onClick = { showFieldPicker = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.edit_more_fields))
            }
        }

        if (state.saveFailed) {
            Spacer(Modifier.height(gap))
            FieldError(R.string.edit_save_failed)
        }
    }

    pickerTarget?.let { target ->
        val current = if (target == PickerTarget.Start) state.start else state.due
        DateTimePickerFlow(
            initial = current,
            allDay = state.isAllDay,
            onResult = {
                if (target == PickerTarget.Start) viewModel.onStartChange(it) else viewModel.onDueChange(it)
                pickerTarget = null
            },
            onDismiss = { pickerTarget = null },
        )
    }

    if (showListPicker) {
        ListPickerDialog(
            lists = state.lists,
            selectedId = state.listId,
            onSelect = { viewModel.onListChange(it); showListPicker = false },
            onDismiss = { showListPicker = false },
        )
    }

    if (showReminderPicker) {
        ReminderPickerDialog(
            selected = state.reminderMinutesBeforeDue,
            onSelect = { viewModel.onReminderChange(it); showReminderPicker = false },
            onDismiss = { showReminderPicker = false },
        )
    }

    if (showFieldPicker) {
        FieldPickerDialog(
            hiddenFields = state.hiddenFields,
            onSelect = { viewModel.revealField(it); showFieldPicker = false },
            onDismiss = { showFieldPicker = false },
        )
    }
}

/**
 * Wrapper for an optional form section: fields added via "More fields" spring
 * open instead of popping in. (Initially-visible sections render without
 * animating on the first frame they're added.)
 */
@Composable
private fun OptionalFormSection(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * One info card mirroring the detail screen's card: tonal container, leading
 * icon in the gutter, value to the right. Optionally clickable as a whole.
 */
@Composable
private fun EditCard(
    icon: ImageVector,
    iconContentDescription: String?,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    // Multiline-input cards align the icon with the field's first text line.
    iconAtTop: Boolean = false,
    // Tall cards pass their first row here: the icon centres on it, and
    // [content] continues below, indented to the same text column.
    header: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    val inner: @Composable () -> Unit = {
        if (header != null) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, iconContentDescription, tint = iconTint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f), content = header)
                }
                Column(modifier = Modifier.padding(start = 40.dp), content = content)
            }
        } else {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = if (iconAtTop) Alignment.Top else Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    tint = iconTint,
                    // 4dp mirrors InlineField's vertical padding, so a
                    // top-aligned icon centres on the first text line.
                    modifier = Modifier
                        .padding(top = if (iconAtTop) 4.dp else 0.dp)
                        .size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f), content = content)
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, color = color, shape = shape, modifier = Modifier.fillMaxWidth()) { inner() }
    } else {
        Surface(color = color, shape = shape, modifier = Modifier.fillMaxWidth()) { inner() }
    }
}

/**
 * Borderless input used inside the cards (and as the headline title) — a thin
 * wrapper over the shared [InlineTextField] so the form and the rest of the app
 * share one input style.
 */
@Composable
private fun InlineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    InlineTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        textStyle = textStyle,
        singleLine = singleLine,
        minLines = minLines,
    )
}

/**
 * One schedule row: label, then the tappable date and (unless all-day) time —
 * or "Set" when empty. Tapping runs the date → time picker flow; a clear
 * affordance appears once a value is set. Tappable values read as links
 * (primary); an error flips them to the error colour.
 */
@Composable
private fun ScheduleRow(
    label: String,
    value: Instant?,
    allDay: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    isError: Boolean = false,
) {
    val valueColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (value == null) {
            Text(
                text = stringResource(R.string.edit_set),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onPick).padding(vertical = 8.dp, horizontal = 6.dp),
            )
        } else {
            Text(
                text = value.formatDate(),
                style = MaterialTheme.typography.titleMedium,
                color = valueColor,
                modifier = Modifier.clickable(onClick = onPick).padding(vertical = 8.dp, horizontal = 6.dp),
            )
            if (!allDay) {
                Text(
                    text = value.formatTime(),
                    style = MaterialTheme.typography.titleMedium,
                    color = valueColor,
                    modifier = Modifier.clickable(onClick = onPick).padding(vertical = 8.dp, horizontal = 6.dp),
                )
            }
            IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.Clear,
                    contentDescription = stringResource(R.string.edit_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun nowInstant(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())

/** Pick a date, then (unless all-day) a time; emits the combined instant once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePickerFlow(
    initial: Instant?,
    allDay: Boolean,
    onResult: (Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    var showTime by remember { mutableStateOf(false) }

    if (!showTime) {
        val initialMillis = (initial ?: nowInstant())
            .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    val millis = dateState.selectedDateMillis ?: run { onDismiss(); return@TextButton }
                    val date = java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    if (allDay) {
                        onResult(localToInstant(date, LocalTime.MIDNIGHT))
                    } else {
                        pendingDate = date
                        showTime = true
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            },
        ) { DatePicker(state = dateState) }
    } else {
        val base = initial ?: nowInstant()
        val timeState = rememberTimePickerState(
            initialHour = base.toLocalTime().hour,
            initialMinute = base.toLocalTime().minute,
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val date = pendingDate ?: return@TextButton
                    onResult(localToInstant(date, LocalTime.of(timeState.hour, timeState.minute)))
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ListPickerDialog(
    lists: List<TaskList>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_list_label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lists.forEach { list ->
                    OptionCard(
                        label = list.name,
                        onClick = { onSelect(list.id) },
                        icon = Icons.Rounded.Circle,
                        iconTint = pastelize(list.color, dark),
                        supportingText = list.accountName.takeIf { it.isNotBlank() },
                        selected = list.id == selectedId,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun ReminderPickerDialog(
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_reminder_label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                reminderOptions.forEach { option ->
                    OptionCard(
                        label = stringResource(option.labelRes),
                        onClick = { onSelect(option.minutes) },
                        selected = option.minutes == selected,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/** Picks one hidden optional section to add to the form. */
@Composable
private fun FieldPickerDialog(
    hiddenFields: List<TaskFormField>,
    onSelect: (TaskFormField) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_more_fields)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hiddenFields.forEach { field ->
                    OptionCard(
                        label = stringResource(fieldLabel(field)),
                        onClick = { onSelect(field) },
                        icon = fieldIcon(field),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

private fun fieldLabel(field: TaskFormField): Int = when (field) {
    TaskFormField.Description -> R.string.field_description
    TaskFormField.Priority -> R.string.edit_priority_label
    TaskFormField.Reminder -> R.string.edit_reminder_label
}

private fun fieldIcon(field: TaskFormField): ImageVector = when (field) {
    TaskFormField.Description -> Icons.AutoMirrored.Rounded.Notes
    TaskFormField.Priority -> Icons.Rounded.Flag
    TaskFormField.Reminder -> Icons.Rounded.Notifications
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

private fun reminderOptionFor(minutes: Int?): ReminderOption =
    reminderOptions.firstOrNull { it.minutes == minutes } ?: reminderOptions.first()

@Composable
private fun FieldError(messageRes: Int) {
    Text(
        text = stringResource(messageRes),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 4.dp),
    )
}

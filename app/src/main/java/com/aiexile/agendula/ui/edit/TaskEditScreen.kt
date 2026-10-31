package com.aiexile.agendula.ui.edit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextOverflow
import com.aiexile.agendula.ui.common.TagChip
import com.aiexile.agendula.ui.common.LocalFirstDayOfWeek
import com.aiexile.agendula.ui.common.LocalUse24HourFormat
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.rounded.Link
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.aiexile.agendula.ui.common.RecurringScopeDialog
import com.aiexile.agendula.ui.common.ReminderAnchorToggle
import com.aiexile.agendula.ui.common.ReminderLeadPicker
import com.aiexile.agendula.ui.common.icon
import com.aiexile.agendula.ui.common.accountLabel
import com.aiexile.agendula.ui.common.labelRes
import com.aiexile.agendula.ui.common.recurrenceText
import com.aiexile.agendula.ui.common.TimePickerAlert
import com.aiexile.agendula.ui.common.formatMinuteOfDay
import com.aiexile.agendula.ui.common.taskReminderLabel
import de.jeanlucmakiola.floret.components.revealOnFocus
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedListInset
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.GroupedSurface
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.locale.currentLocale
import de.jeanlucmakiola.floret.reminders.ReminderOverride
import kotlinx.datetime.toKotlinDayOfWeek
import kotlinx.datetime.toKotlinLocalDate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiexile.agendula.R
import de.jeanlucmakiola.floret.time.DayWindow
import com.aiexile.agendula.domain.Priority
import com.aiexile.agendula.domain.Task
import com.aiexile.agendula.domain.TaskFormError
import com.aiexile.agendula.domain.TaskFormField
import com.aiexile.agendula.domain.TaskList
import com.aiexile.agendula.domain.TaskReminder
import com.aiexile.agendula.domain.DAY_MINUTES
import com.aiexile.agendula.domain.TaskSection
import com.aiexile.agendula.domain.TaskSections
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.OptionalFormSection
import de.jeanlucmakiola.floret.components.InlineTextField
import com.aiexile.agendula.ui.common.priorityFill
import de.jeanlucmakiola.floret.time.formatDate
import de.jeanlucmakiola.floret.time.formatTime
import com.aiexile.agendula.ui.common.localToInstant
import de.jeanlucmakiola.floret.components.pastelize
import de.jeanlucmakiola.floret.components.positionOf
import com.aiexile.agendula.domain.allDayInstantOf
import com.aiexile.agendula.domain.calendarDate
import com.aiexile.agendula.ui.common.toLocalTime
import com.aiexile.agendula.ui.tasklist.priorityLabel
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Create / edit form, in the family's tonal-card language (mirrors Calendula's
 * event editor): a borderless headline title with a list-colour accent bar, a
 * "When" card with the all-day toggle and tappable schedule rows, a tappable
 * list card, and the optional Description / Priority / Reminder sections that
 * unfold from "More fields" (which ones start open is a setting). Validated
 * through the M1 [TaskEditViewModel] / [com.aiexile.agendula.domain.TaskForm];
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
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var showScope by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    val requestBack = { if (state.isDirty && !state.saved) showDiscard = true else onBack() }
    BackHandler(enabled = state.isDirty && !state.saved) { showDiscard = true }

    // Once the title field scrolls away it rides in the bar, so a long form never
    // loses track of which task is being edited.
    val scroll = rememberScrollState()
    var titleBottom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val titleInBar by remember { derivedStateOf { scroll.value > titleBottom } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(
                        visible = titleInBar && state.title.isNotBlank(),
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically { it / 2 },
                    ) {
                        Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    Button(
                        onClick = { if (viewModel.needsScope()) showScope = true else viewModel.save() },
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
            scroll = scroll,
            onTitleBottom = { titleBottom = it },
            modifier = Modifier.padding(inner),
        )
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(stringResource(R.string.edit_discard_body)) },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; onBack() }) {
                    Text(stringResource(R.string.edit_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) { Text(stringResource(R.string.edit_keep_editing)) }
            },
        )
    }

    if (showScope) {
        RecurringScopeDialog(
            title = stringResource(R.string.edit_scope_title),
            allowOccurrence = !state.recurrenceChanged,
            onSelect = { scope ->
                showScope = false
                viewModel.save(scope = scope)
            },
            onDismiss = { showScope = false },
        )
    }
}

private enum class PickerTarget { Start, Due }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditContent(
    state: TaskEditUiState,
    viewModel: TaskEditViewModel,
    scroll: ScrollState,
    onTitleBottom: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val selectedList = state.lists.firstOrNull { it.id == state.listId }
    // The accent ties the form to the detail screen's language: the bar under
    // the title takes the target list's colour.
    val accent = selectedList?.let { pastelize(it.color, dark) } ?: MaterialTheme.colorScheme.primary
    val gap = 12.dp
    // A reminder's anchor only says something when the task has both to choose from.
    val bothDates = state.start != null && state.due != null

    var pickerTarget by rememberSaveable { mutableStateOf<PickerTarget?>(null) }
    var showListPicker by rememberSaveable { mutableStateOf(false) }
    var showParentPicker by rememberSaveable { mutableStateOf(false) }
    var showReminderPicker by rememberSaveable { mutableStateOf(false) }
    var showFieldPicker by rememberSaveable { mutableStateOf(false) }
    var showRecurrencePicker by rememberSaveable { mutableStateOf(false) }

    // A fresh, still-empty form takes the cursor once; an edit or a prefilled
    // title never grabs focus.
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(state.loading) {
        if (!state.loading && state.autofocusTitle && state.isNew && state.title.isBlank()) {
            runCatching { titleFocus.requestFocus() }
        }
    }

    Column(
        modifier = modifier
            // Shrink the scroll viewport by the keyboard so the focused field
            // scrolls into view above the IME instead of being hidden.
            .imePadding()
            .verticalScroll(scroll)
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 40.dp),
    ) {
        // Title: borderless headline + accent bar, mirroring the detail screen.
        InlineField(
            value = state.title,
            onValueChange = viewModel::onTitleChange,
            placeholder = stringResource(R.string.edit_title_hint),
            textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .focusRequester(titleFocus)
                .onPlaced { onTitleBottom(it.positionInParent().y.toInt() + it.size.height) },
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
            onClick = { showListPicker = true }.takeIf { state.writableLists.isNotEmpty() },
        ) {
            Text(
                text = selectedList?.name ?: stringResource(R.string.error_no_list),
                style = MaterialTheme.typography.titleMedium,
                color = if (selectedList == null) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            selectedList?.let { list ->
                val account = accountLabel(list.accountName, list.isLocal)
                if (account.isNotBlank()) {
                    Text(
                        text = account,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

        OptionalFormSection(visible = TaskFormField.Recurrence in state.visibleFields) {
            Spacer(Modifier.height(gap))
            val locale = currentLocale()
            EditCard(
                icon = Icons.Rounded.Repeat,
                iconContentDescription = null,
                onClick = { showRecurrencePicker = true },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.rrule?.let { recurrenceText(it, locale) }
                                ?: AnnotatedString(stringResource(R.string.edit_recurrence_none)),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.edit_recurrence_label),
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
                if (TaskFormError.RECURRENCE_WITHOUT_DATE in state.errors) {
                    Spacer(Modifier.height(2.dp))
                    FieldError(R.string.error_recurrence_without_date)
                }
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
                    // The M3 single-select segmented row, but the active segment
                    // takes its priority hue so the choice reads in colour.
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        Priority.entries.forEachIndexed { index, priority ->
                            val fill = priorityFill(priority, dark)
                            val active = fill.isSpecified
                            SegmentedButton(
                                selected = state.priority == priority,
                                onClick = { viewModel.onPriorityChange(priority) },
                                shape = SegmentedButtonDefaults.itemShape(index, Priority.entries.size),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = if (active) fill else MaterialTheme.colorScheme.secondaryContainer,
                                    activeContentColor = when {
                                        !active -> MaterialTheme.colorScheme.onSecondaryContainer
                                        fill.luminance() > 0.5f -> Color.Black
                                        else -> Color.White
                                    },
                                ),
                                // Drop the selected-check icon; with four buttons its
                                // reserved width is what crowds the labels.
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

        OptionalFormSection(visible = TaskFormField.Progress in state.visibleFields) {
            Spacer(Modifier.height(gap))
            val pct = state.percentComplete ?: 0
            // Full-width like Priority: the slider needs the card's whole width,
            // so the label/readout sit on a header row above it.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Percent,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.edit_progress_label),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.percent_complete, pct),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = pct.toFloat(),
                        onValueChange = { viewModel.onPercentChange(it.toInt()) },
                        valueRange = 0f..100f,
                        steps = 19, // 5% detents
                    )
                }
            }
        }

        OptionalFormSection(visible = TaskFormField.Parent in state.visibleFields) {
            Spacer(Modifier.height(gap))
            EditCard(
                icon = Icons.Rounded.AccountTree,
                iconContentDescription = stringResource(R.string.edit_parent_label),
                onClick = { showParentPicker = true },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.parent?.title?.ifBlank { stringResource(R.string.task_untitled) }
                                ?: stringResource(R.string.edit_parent_none),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.edit_parent_label),
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
            }
        }

        OptionalFormSection(visible = TaskFormField.Reminder in state.visibleFields) {
            Spacer(Modifier.height(gap))
            // One card per reminder, connected, the last row adding another.
            val rows = state.reminders.size + 1
            state.reminders.forEachIndexed { index, reminder ->
                ReminderItemCard(
                    position = positionOf(index, rows),
                    title = taskReminderLabel(reminder, state.isAllDay, state.allDayMinuteOfDay, anchored = bothDates),
                    onRemove = { viewModel.removeReminder(reminder) },
                )
            }
            AddReminderCard(
                position = positionOf(state.reminders.size, rows),
                label = stringResource(
                    // Only a set the user has not chosen still falls back to the default.
                    if (state.reminders.isEmpty() && !state.remindersChosen) R.string.edit_reminder_add_first
                    else R.string.edit_reminder_add,
                ),
                onClick = { showReminderPicker = true },
            )
            if (TaskFormError.REMINDER_WITHOUT_DATE in state.errors) {
                Spacer(Modifier.height(4.dp))
                FieldError(R.string.error_reminder_without_date)
            }
        }

        OptionalFormSection(visible = TaskFormField.Location in state.visibleFields) {
            Spacer(Modifier.height(gap))
            EditCard(
                icon = Icons.Rounded.LocationOn,
                iconContentDescription = stringResource(R.string.edit_location_label),
            ) {
                InlineField(
                    value = state.location,
                    onValueChange = viewModel::onLocationChange,
                    placeholder = stringResource(R.string.edit_location_label),
                )
            }
        }

        OptionalFormSection(visible = TaskFormField.Url in state.visibleFields) {
            Spacer(Modifier.height(gap))
            EditCard(
                icon = Icons.Rounded.Link,
                iconContentDescription = stringResource(R.string.edit_url_label),
            ) {
                InlineField(
                    value = state.url,
                    onValueChange = viewModel::onUrlChange,
                    placeholder = stringResource(R.string.edit_url_hint),
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                )
            }
        }

        OptionalFormSection(visible = TaskFormField.Tags in state.visibleFields) {
            Spacer(Modifier.height(gap))
            TagsCard(
                tags = state.tags,
                draft = state.tagDraft,
                suggestions = state.tagSuggestions,
                onDraftChange = viewModel::onTagDraftChange,
                onCommitDraft = viewModel::commitTagDraft,
                onAdd = viewModel::addTag,
                onRemove = viewModel::removeTag,
            )
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
            lists = state.writableLists,
            selectedId = state.listId,
            onSelect = { viewModel.onListChange(it); showListPicker = false },
            onDismiss = { showListPicker = false },
        )
    }

    if (showParentPicker) {
        ParentPickerSheet(
            candidates = state.parentCandidates,
            selectedId = state.parentId,
            onSelect = { viewModel.onParentChange(it); showParentPicker = false },
            onDismiss = { showParentPicker = false },
        )
    }

    if (state.saveConflict) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConflict,
            title = { Text(stringResource(R.string.edit_conflict_title)) },
            text = { Text(stringResource(R.string.edit_conflict_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.save(force = true) }) {
                    Text(stringResource(R.string.edit_conflict_overwrite))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConflict) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    if (showReminderPicker) {
        var fromStart by rememberSaveable { mutableStateOf(state.start != null) }
        var minuteOfDay by rememberSaveable { mutableIntStateOf(state.allDayMinuteOfDay) }
        var pickingTime by rememberSaveable { mutableStateOf(false) }
        ReminderLeadPicker(
            title = stringResource(R.string.edit_reminder_add),
            selected = ReminderOverride.Inherit,
            allowInherit = false,
            allowNone = false,
            allDay = state.isAllDay,
            header = if (bothDates || state.isAllDay) {
                {
                    if (bothDates) {
                        ReminderAnchorToggle(fromStart = fromStart, onChange = { fromStart = it })
                        Spacer(Modifier.height(16.dp))
                    }
                    if (state.isAllDay) {
                        GroupedRow(
                            title = stringResource(R.string.reminder_time),
                            position = Position.Alone,
                            trailing = {
                                Text(
                                    formatMinuteOfDay(minuteOfDay, LocalUse24HourFormat.current),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = { pickingTime = true },
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            } else {
                null
            },
            onSelect = { choice ->
                (choice as? ReminderOverride.Minutes)?.minutes?.firstOrNull()?.let { minutes ->
                    // A task with one date counts from that one, whatever the toggle said.
                    val anchorStart = if (bothDates) fromStart else state.due == null
                    viewModel.addReminder(
                        if (state.isAllDay) {
                            // The default time is left unnamed, so the reminder follows the setting.
                            TaskReminder.allDay(
                                daysBefore = minutes / DAY_MINUTES,
                                minuteOfDay = minuteOfDay.takeIf { it != state.allDayMinuteOfDay },
                                fromStart = anchorStart,
                            )
                        } else {
                            TaskReminder(minutes, fromStart = anchorStart)
                        },
                    )
                }
                showReminderPicker = false
            },
            onDismiss = { showReminderPicker = false },
        )
        if (pickingTime) {
            TimePickerAlert(
                initial = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60),
                onConfirm = {
                    val picked = it.hour * 60 + it.minute
                    // The default time stays unnamed; any other is one an all-day reminder can name.
                    minuteOfDay = if (picked == state.allDayMinuteOfDay) picked else TaskReminder.allDayMinuteOfDay(picked)
                    pickingTime = false
                },
                onDismiss = { pickingTime = false },
            )
        }
    }

    if (showRecurrencePicker) {
        val anchor = (state.start ?: state.due)?.calendarDate(state.isAllDay) ?: java.time.LocalDate.now()
        RecurrencePicker(
            current = state.rrule,
            startDate = anchor.toKotlinLocalDate(),
            firstDayOfWeek = LocalFirstDayOfWeek.current.toKotlinDayOfWeek(),
            onSelect = { viewModel.onRecurrenceChange(it); showRecurrencePicker = false },
            onDismiss = { showRecurrencePicker = false },
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
        Surface(onClick = onClick, color = color, shape = shape, modifier = Modifier.fillMaxWidth().revealOnFocus()) { inner() }
    } else {
        Surface(color = color, shape = shape, modifier = Modifier.fillMaxWidth().revealOnFocus()) { inner() }
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
    modifier: Modifier = Modifier,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
) {
    InlineTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).then(modifier),
        textStyle = textStyle,
        singleLine = singleLine,
        minLines = minLines,
        keyboardType = keyboardType,
        capitalization = capitalization,
    )
}

/**
 * One schedule row: label, then the tappable date and (unless all-day) time —
 * or "Set" when empty. Tapping runs the date → time picker flow; a clear
 * affordance appears once a value is set. Tappable values read as links
 * (primary); an error flips them to the error colour.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val valueModifier = Modifier.clickable(onClick = onPick).padding(vertical = 8.dp, horizontal = 6.dp)
    LabelledValueLayout(
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        },
        value = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value == null) {
                    Text(
                        text = stringResource(R.string.edit_set),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = valueModifier,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.End,
                        itemVerticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        Text(
                            text = value.formatDate(allDay),
                            style = MaterialTheme.typography.titleMedium,
                            color = valueColor,
                            modifier = valueModifier,
                        )
                        if (!allDay) {
                            Text(
                                text = value.formatTime(LocalUse24HourFormat.current),
                                style = MaterialTheme.typography.titleMedium,
                                color = valueColor,
                                modifier = valueModifier,
                            )
                        }
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
        },
    )
}

/**
 * [label] at the start and [value] at the end of one line when both fit, else
 * [value] on its own line below, still end-aligned — so large font scales wrap
 * rather than clip either side (#76).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelledValueLayout(label: @Composable () -> Unit, value: @Composable () -> Unit) {
    FlowRow(
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        label()
        // Its whole width as its minimum, so the value wraps as one piece.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.width(IntrinsicSize.Max)) { value() }
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
    var pendingDate by rememberSaveable { mutableStateOf<java.time.LocalDate?>(null) }
    var showTime by rememberSaveable { mutableStateOf(false) }

    if (!showTime) {
        // M3's DatePicker speaks UTC millis. An all-day value is already UTC-based,
        // a timed one is read in the device zone — calendarDate picks the right frame
        // so the dialog opens on the day the rest of the UI shows.
        val initialMillis = (initial ?: nowInstant())
            .calendarDate(allDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    val millis = dateState.selectedDateMillis ?: run { onDismiss(); return@TextButton }
                    val date = java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    if (allDay) {
                        onResult(allDayInstantOf(date))
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
            is24Hour = LocalUse24HourFormat.current,
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

/** The target list, full-screen and grouped under each list's account. */
@Composable
private fun ListPickerDialog(
    lists: List<TaskList>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val groups = remember(lists) { lists.groupBy { it.accountName } }
    FullScreenPicker(title = stringResource(R.string.edit_list_label), onDismiss = onDismiss) {
        groups.forEach { (account, inAccount) ->
            if (groups.size > 1 && account.isNotBlank()) {
                // A device-only store's account is a placeholder in the data layer.
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

/**
 * Pick the task to file this one under, or "None" to keep it top-level. A search
 * field filters by title, and candidates group under the same due-date sections as
 * the task list (Overdue / Today / Upcoming / No date). Only active tasks are
 * offered (the VM filters closed ones out).
 */
@Composable
private fun ParentPickerSheet(
    candidates: List<Task>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(candidates, query) {
        val q = query.trim()
        if (q.isEmpty()) candidates else candidates.filter { it.title.contains(q, ignoreCase = true) }
    }
    val (todayStart, todayEnd) = remember { DayWindow.today(Clock.System.now(), ZoneId.systemDefault()) }
    val sections = remember(filtered) { TaskSections.of(filtered, todayStart, todayEnd) }

    FullScreenPicker(title = stringResource(R.string.edit_parent_label), onDismiss = onDismiss) {
        GroupedSurface(
            position = Position.Alone,
            modifier = Modifier.padding(horizontal = GroupedListInset),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Box(modifier = Modifier.weight(1f)) {
                    InlineTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(R.string.edit_parent_search),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.edit_clear))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GroupedRow(
            title = stringResource(R.string.edit_parent_none),
            position = Position.Alone,
            selected = selectedId == null,
            trailing = if (selectedId == null) ({ SelectedCheck() }) else null,
            minHeight = 56.dp,
            onClick = { onSelect(null) },
        )
        if (sections.isEmpty()) {
            Text(
                text = stringResource(R.string.edit_parent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
            )
        }
        sections.forEach { section ->
            GroupedSectionHeader(parentSectionLabel(section.section))
            section.tasks.forEachIndexed { index, task ->
                val selected = task.taskId == selectedId
                GroupedRow(
                    title = task.title.ifBlank { stringResource(R.string.task_untitled) },
                    position = positionOf(index, section.tasks.size),
                    summary = task.due?.formatDate(task.isAllDay),
                    selected = selected,
                    trailing = if (selected) ({ SelectedCheck() }) else null,
                    minHeight = 56.dp,
                    onClick = { onSelect(task.taskId) },
                )
            }
        }
    }
}

@Composable
private fun parentSectionLabel(section: TaskSection): String = stringResource(
    when (section) {
        TaskSection.OVERDUE -> R.string.section_overdue
        TaskSection.TODAY -> R.string.section_today
        TaskSection.UPCOMING -> R.string.section_upcoming
        TaskSection.NO_DATE -> R.string.section_no_date
        TaskSection.COMPLETED -> R.string.section_completed
    },
)

/** One reminder in the connected run, with a remove button. */
@Composable
private fun ReminderItemCard(position: Position, title: String, onRemove: () -> Unit) {
    GroupedSurface(position = position, gapBelow = position != Position.Bottom && position != Position.Alone) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(Icons.Rounded.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.edit_reminder_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** The run's last row: add a reminder. */
@Composable
private fun AddReminderCard(position: Position, label: String, onClick: () -> Unit) {
    GroupedSurface(position = position, onClick = onClick, gapBelow = false) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The input sits beside the icon; the tags and any suggestions run below it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsCard(
    tags: List<String>,
    draft: String,
    suggestions: List<String>,
    onDraftChange: (String) -> Unit,
    onCommitDraft: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val query = draft.trim()
    val matches = if (query.isEmpty()) {
        emptyList()
    } else {
        suggestions.filter { it.contains(query, ignoreCase = true) && it !in tags }.take(MAX_TAG_SUGGESTIONS)
    }
    EditCard(
        icon = Icons.Rounded.Sell,
        iconContentDescription = stringResource(R.string.edit_tags_label),
        header = {
            InlineTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = stringResource(R.string.edit_tags_hint),
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
                onImeAction = onCommitDraft,
            )
        },
    ) {
        if (matches.isNotEmpty()) {
            TagFlow(modifier = Modifier.padding(top = 8.dp)) {
                matches.forEach { match ->
                    TagChip(
                        name = match,
                        muted = true,
                        icon = Icons.Rounded.Add,
                        onClick = { onAdd(match) },
                    )
                }
            }
        }
        if (tags.isNotEmpty()) {
            TagFlow(modifier = Modifier.padding(top = 8.dp)) {
                tags.forEach { tag ->
                    TagChip(
                        name = tag,
                        icon = Icons.Rounded.Close,
                        iconContentDescription = stringResource(R.string.edit_tag_remove, tag),
                        onClick = { onRemove(tag) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

private const val MAX_TAG_SUGGESTIONS = 8

/** Picks one hidden optional section to add to the form. */
@Composable
private fun FieldPickerDialog(
    hiddenFields: List<TaskFormField>,
    onSelect: (TaskFormField) -> Unit,
    onDismiss: () -> Unit,
) {
    FullScreenPicker(title = stringResource(R.string.edit_more_fields), onDismiss = onDismiss) {
        hiddenFields.forEachIndexed { index, field ->
            GroupedRow(
                title = stringResource(field.labelRes()),
                position = positionOf(index, hiddenFields.size),
                leading = { Icon(field.icon(), contentDescription = null) },
                onClick = { onSelect(field) },
            )
        }
    }
}

@Composable
private fun FieldError(messageRes: Int) {
    Text(
        text = stringResource(messageRes),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 4.dp),
    )
}

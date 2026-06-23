package de.jeanlucmakiola.floret.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.floret.R
import de.jeanlucmakiola.floret.domain.Task
import de.jeanlucmakiola.floret.domain.TaskDetail
import de.jeanlucmakiola.floret.ui.common.GroupedSurface
import de.jeanlucmakiola.floret.ui.common.formatDate
import de.jeanlucmakiola.floret.ui.common.formatTime
import de.jeanlucmakiola.floret.ui.common.pastelize
import de.jeanlucmakiola.floret.ui.common.positionOf
import de.jeanlucmakiola.floret.ui.tasklist.priorityLabel
import de.jeanlucmakiola.floret.ui.tasklist.priorityTint

/**
 * One task's detail. Each fact is its own tonal card with a leading icon (the
 * Calendula detail pattern); only genuinely-grouped things — the subtasks — use
 * the grouped-row run. Edit / delete live in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.task_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    val content = state as? TaskDetailUiState.Content
                    IconButton(onClick = onEdit, enabled = content != null) {
                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.edit))
                    }
                    IconButton(
                        onClick = {
                            content?.let {
                                viewModel.delete(it.detail.task)
                                onDeleted()
                            }
                        },
                        enabled = content != null,
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        },
    ) { inner ->
        when (val s = state) {
            TaskDetailUiState.Loading -> Unit
            TaskDetailUiState.NotFound ->
                CenteredMessage(stringResource(R.string.task_detail_not_found), inner)
            is TaskDetailUiState.Content -> DetailBody(
                detail = s.detail,
                inner = inner,
                onToggle = { viewModel.toggleComplete(s.detail.task) },
                onToggleSubtask = { viewModel.toggleComplete(it) },
                onAddSubtask = { viewModel.addSubtask(it) },
                onOpenSubtask = { /* reserved: subtask navigation lands with the subtasks milestone */ },
            )
        }
    }
}

@Composable
private fun DetailBody(
    detail: TaskDetail,
    inner: androidx.compose.foundation.layout.PaddingValues,
    onToggle: () -> Unit,
    onToggleSubtask: (Task) -> Unit,
    onAddSubtask: (String) -> Unit,
    onOpenSubtask: (Long) -> Unit,
) {
    val task = detail.task
    val dark = isSystemInDarkTheme()
    val accent = pastelize(task.effectiveColor, dark)
    val gap = 12.dp
    var subtasksExpanded by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(inner)
            // Shrink the scroll viewport by the keyboard so the focused add-subtask
            // field scrolls into view above the IME instead of being hidden.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 40.dp),
    ) {
        // Title + complete toggle, with a short list-coloured accent beneath.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = task.isCompleted, onCheckedChange = { onToggle() })
            Text(
                text = task.title.ifBlank { stringResource(R.string.task_untitled) },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(3.dp)
                .background(accent, RoundedCornerShape(2.dp)),
        )

        Spacer(Modifier.height(20.dp))

        // One card per fact; gaps between, no shared container (they aren't a group).
        val cards = buildList<@Composable () -> Unit> {
            // When — the Calendula time card: date primary, time(s) secondary.
            taskWhenLines(task)?.let { (primary, secondary) ->
                add {
                    DetailCard(icon = Icons.Rounded.Schedule, iconContentDescription = stringResource(R.string.detail_due)) {
                        Text(primary, style = MaterialTheme.typography.titleMedium)
                        if (secondary != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                secondary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // List — icon tinted in the list colour conveys identity (no extra dot).
            add {
                DetailCard(
                    icon = Icons.Rounded.Checklist,
                    iconTint = accent,
                    iconContentDescription = stringResource(R.string.detail_list),
                ) {
                    Text(
                        text = task.listName ?: stringResource(R.string.detail_list),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Priority — the flag moves to the front, tinted like the list rows.
            if (task.priority != de.jeanlucmakiola.floret.domain.Priority.NONE) {
                add {
                    DetailCard(
                        icon = Icons.Rounded.Flag,
                        iconTint = priorityTint(task.priority),
                        iconContentDescription = stringResource(R.string.detail_priority),
                    ) {
                        Text(priorityLabel(task.priority), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            // Progress — only meaningful with subtasks. Uses the provider's
            // percent when set, otherwise the subtask completion ratio.
            if (detail.subtasks.isNotEmpty()) {
                val total = detail.subtasks.size
                val doneCount = detail.subtasks.count { it.isCompleted }
                val pct = task.percentComplete ?: (doneCount * 100 / total)
                add {
                    DetailCard(icon = Icons.Rounded.Percent, iconContentDescription = stringResource(R.string.detail_progress)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.detail_progress), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.percent_complete, pct),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // Description.
            task.description?.takeIf { it.isNotBlank() }?.let { description ->
                add {
                    DetailCard(icon = Icons.AutoMirrored.Rounded.Notes, iconContentDescription = null) {
                        Text(description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Subtasks — a genuine group: a connected grouped run whose first
            // segment is the expand/collapse toggle. Always shown (even with no
            // subtasks yet) so the add field is reachable on any task.
            add {
                SubtasksGroup(
                    subtasks = detail.subtasks,
                    expanded = subtasksExpanded,
                    onToggle = { subtasksExpanded = !subtasksExpanded },
                    onToggleSubtask = onToggleSubtask,
                    onAddSubtask = onAddSubtask,
                    onOpenSubtask = onOpenSubtask,
                )
            }
        }
        cards.forEachIndexed { index, card ->
            if (index > 0) Spacer(Modifier.height(gap))
            card()
        }
    }
}

/**
 * Subtasks as a connected grouped run (the GroupedRow family look) at the
 * cards' width: the first segment is the expand/collapse toggle (icon,
 * "Subtasks", a done/total count, chevron); the rest are the subtask rows.
 */
@Composable
private fun SubtasksGroup(
    subtasks: List<Task>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onToggleSubtask: (Task) -> Unit,
    onAddSubtask: (String) -> Unit,
    onOpenSubtask: (Long) -> Unit,
) {
    val done = subtasks.count { it.isCompleted }
    // Completed subtasks sink to the bottom; stable, so order is otherwise kept.
    val ordered = remember(subtasks) { subtasks.sortedBy { it.isCompleted } }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "subtasksChevron")
    // Segments when expanded: header + the always-present add row + each subtask.
    val count = if (expanded) subtasks.size + 2 else 1

    // First segment: the toggle header.
    GroupedSurface(position = positionOf(0, count), onClick = onToggle) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ListAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.detail_subtasks),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            // No count until there's something to count.
            if (subtasks.isNotEmpty()) {
                Text(
                    text = "$done / ${subtasks.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotation),
            )
        }
    }

    if (expanded) {
        // Always-present add row at the top of the list, in an accent tone.
        GroupedSurface(
            position = positionOf(1, count),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            AddSubtaskField(onAdd = onAddSubtask)
        }
        ordered.forEachIndexed { index, sub ->
            GroupedSurface(
                position = positionOf(index + 2, count),
                onClick = { onOpenSubtask(sub.taskId) },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = sub.isCompleted, onCheckedChange = { onToggleSubtask(sub) })
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = sub.title.ifBlank { stringResource(R.string.task_untitled) },
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (sub.isCompleted) TextDecoration.LineThrough else null,
                        color = if (sub.isCompleted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Inline "add a subtask" — title only; submits on the tick or the IME action.
 * A [BasicTextField] (not the boxed Material field) so its text lines up exactly
 * with the subtask titles, the checkbox column matched by the leading + icon.
 */
@Composable
private fun AddSubtaskField(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text.trim())
            text = ""
        }
    }
    val accentOn = MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = accentOn)
        }
        Spacer(Modifier.width(4.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = accentOn),
            cursorBrush = SolidColor(accentOn),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.add_task_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = accentOn.copy(alpha = 0.7f),
                    )
                }
                inner()
            },
        )
        if (text.isNotBlank()) {
            IconButton(onClick = ::submit) {
                Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.cd_add_task), tint = accentOn)
            }
        }
    }
}

/** One info card: tonal container, leading icon in the gutter, value to the right. */
@Composable
private fun DetailCard(
    icon: ImageVector,
    iconContentDescription: String?,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = iconContentDescription,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), content = content)
        }
    }
}

/**
 * The task's "when", formatted like Calendula's event time card: a primary date
 * line and an optional secondary time line. A start+due pair reads as a range;
 * `null` when the task has no dates at all.
 */
@Composable
private fun taskWhenLines(task: Task): Pair<String, String?>? {
    val start = task.start
    val due = task.due
    return when {
        start != null && due != null -> {
            val sameDay = start.formatDate() == due.formatDate()
            val primary = if (sameDay) due.formatDate() else "${start.formatDate()} – ${due.formatDate()}"
            val secondary = if (task.isAllDay) null else "${start.formatTime()} – ${due.formatTime()}"
            primary to secondary
        }
        due != null -> due.formatDate() to if (task.isAllDay) null else due.formatTime()
        start != null -> start.formatDate() to if (task.isAllDay) null else start.formatTime()
        else -> null
    }
}

@Composable
private fun CenteredMessage(text: String, inner: androidx.compose.foundation.layout.PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(inner).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

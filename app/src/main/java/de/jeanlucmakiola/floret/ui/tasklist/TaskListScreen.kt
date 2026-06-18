package de.jeanlucmakiola.floret.ui.tasklist

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.floret.R
import de.jeanlucmakiola.floret.domain.DayWindow
import de.jeanlucmakiola.floret.domain.Priority
import de.jeanlucmakiola.floret.domain.SmartList
import de.jeanlucmakiola.floret.domain.Task
import de.jeanlucmakiola.floret.domain.TaskFilter
import de.jeanlucmakiola.floret.domain.TaskSection
import de.jeanlucmakiola.floret.domain.TaskSections
import de.jeanlucmakiola.floret.ui.common.ListNameChip
import de.jeanlucmakiola.floret.ui.common.Position
import de.jeanlucmakiola.floret.ui.common.formatDateTime
import de.jeanlucmakiola.floret.ui.common.pastelize
import de.jeanlucmakiola.floret.ui.common.positionOf
import java.time.ZoneId
import kotlin.time.Clock

/**
 * The tasks for one [TaskFilter]: due-date section headers, grouped tonal rows
 * with a leading checkbox, swipe-to-complete (start→end) / swipe-to-delete
 * (end→start), and — for a real list — an inline add field. Renders against the
 * M1 [TaskListViewModel]; actions are fire-and-forget and flow back live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    filter: TaskFilter,
    onOpenTask: (Long) -> Unit,
    onNewTask: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listName = (state as? TaskListUiState.Content)?.listName

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text(titleFor(filter, listName)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewTask,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_task)) },
            )
        },
    ) { inner ->
        when (val s = state) {
            TaskListUiState.Loading -> Unit // brief; avoids a flash before first emission
            TaskListUiState.Failure -> CenteredMessage(stringResource(R.string.task_list_failure), inner)
            is TaskListUiState.Content -> TaskListBody(s, filter, inner, onOpenTask, viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TaskListBody(
    state: TaskListUiState.Content,
    filter: TaskFilter,
    inner: PaddingValues,
    onOpenTask: (Long) -> Unit,
    viewModel: TaskListViewModel,
) {
    val (todayStart, todayEnd) = remember { DayWindow.today(Clock.System.now(), ZoneId.systemDefault()) }
    // A parent represents its children via a progress chip, so drop children
    // from the flat list — unless their parent isn't in this view (can happen in
    // smart lists), where we keep them standalone so nothing silently vanishes.
    val childrenByParent = remember(state.tasks) {
        state.tasks.filter { (it.parentId ?: 0L) > 0L }.groupBy { it.parentId!! }
    }
    val displayTasks = remember(state.tasks) {
        val present = state.tasks.mapTo(HashSet()) { it.taskId }
        state.tasks.filter { (it.parentId ?: 0L) <= 0L || it.parentId !in present }
    }
    val sections = remember(displayTasks) { TaskSections.of(displayTasks, todayStart, todayEnd) }
    val showHeaders = sections.size > 1
    val listId = (filter as? TaskFilter.OfList)?.listId
    // In a mixed view (a smart list) the list colour bar + name disambiguate
    // rows; inside a single real list both are redundant.
    val showListName = filter is TaskFilter.Smart
    // Completed tasks collapse away by default to keep the active list in focus.
    var completedExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = inner.calculateTopPadding() + 8.dp,
            bottom = inner.calculateBottomPadding() + 96.dp,
        ),
    ) {
        if (listId != null) {
            item(key = "inline-add") {
                InlineAdd(onAdd = { title -> viewModel.quickAdd(title, listId) })
            }
        }

        if (state.tasks.isEmpty()) {
            item(key = "empty") { EmptyState(stringResource(R.string.task_list_empty)) }
        }

        sections.forEach { section ->
            val collapsible = showHeaders && section.section == TaskSection.COMPLETED
            if (showHeaders) {
                stickyHeader(key = "hdr-${section.section}") {
                    if (collapsible) {
                        CollapsibleSectionHeader(
                            label = sectionLabel(section.section),
                            count = section.tasks.size,
                            expanded = completedExpanded,
                            onToggle = { completedExpanded = !completedExpanded },
                        )
                    } else {
                        SectionHeader(sectionLabel(section.section))
                    }
                }
            }
            if (!collapsible || completedExpanded) {
                itemsIndexed(section.tasks, key = { _, t -> t.taskId }) { index, task ->
                    val children = childrenByParent[task.taskId]
                    TaskRow(
                        task = task,
                        position = positionOf(index, section.tasks.size),
                        showListName = showListName,
                        subtaskDone = children?.count { it.isCompleted } ?: 0,
                        subtaskTotal = children?.size ?: 0,
                        onToggle = { viewModel.toggleComplete(task) },
                        onDelete = { viewModel.delete(task) },
                        onClick = { onOpenTask(task.taskId) },
                    )
                }
            }
        }
    }
}

/** Inline "add a task" — title only, into the current list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InlineAdd(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text.trim())
            text = ""
        }
    }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.add_task_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            trailingIcon = {
                if (text.isNotBlank()) {
                    IconButton(onClick = ::submit) {
                        Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.cd_add_task))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * One task row: a tonal grouped-card surface (corners from [position], morphing
 * rounder on press — the app's shape family) wrapped in a swipe box. Swiping
 * right toggles complete (snaps back); swiping left deletes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskRow(
    task: Task,
    position: Position,
    showListName: Boolean,
    subtaskDone: Int,
    subtaskTotal: Int,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onToggle(); false } // toggle, snap back
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); true }  // delete, dismiss
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    val gap = when (position) {
        Position.Top, Position.Middle -> Modifier.padding(bottom = 2.dp)
        Position.Bottom, Position.Alone -> Modifier
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(gap),
        backgroundContent = { SwipeBackground(dismissState.targetValue, task.isCompleted) },
    ) {
        TaskRowContent(task, position, showListName, subtaskDone, subtaskTotal, onToggle, onClick)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskRowContent(
    task: Task,
    position: Position,
    showListName: Boolean,
    subtaskDone: Int,
    subtaskTotal: Int,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val full by animateDpAsState(if (pressed) 36.dp else 22.dp, label = "fullCorner")
    val small by animateDpAsState(if (pressed) 36.dp else 6.dp, label = "smallCorner")
    val dark = isSystemInDarkTheme()
    val due = task.due?.formatDateTime(task.isAllDay)
    val listName = task.listName?.takeIf { showListName && it.isNotBlank() }
    val hasSupporting = due != null || listName != null || subtaskTotal > 0

    Surface(
        onClick = onClick,
        shape = cardShape(position, full, small),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Hand-laid row (not ListItem) so a one- vs two-line body never trips
        // ListItem's three-line heuristic and leaves dead space at the bottom.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Calendula's signature accent: a slim pastelised colour bar for the
            // list, paired here with the task's completion checkbox.
            Box(
                modifier = Modifier
                    .size(width = 5.dp, height = 32.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(pastelize(task.effectiveColor, dark)),
            )
            Checkbox(checked = task.isCompleted, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title.ifBlank { stringResource(R.string.task_untitled) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                    color = if (task.isCompleted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (hasSupporting) {
                    Spacer(Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (due != null) {
                            Text(
                                text = due,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (listName != null) ListNameChip(listName, task.effectiveColor)
                        if (subtaskTotal > 0) SubtaskCountChip(subtaskDone, subtaskTotal)
                    }
                }
            }
            priorityFlag(task.priority)?.let { flag ->
                Spacer(Modifier.width(8.dp))
                flag()
            }
        }
    }
}

/** A flag tinted by priority, or nothing for [Priority.NONE]. */
@Composable
private fun priorityFlag(priority: Priority): (@Composable () -> Unit)? {
    if (priority == Priority.NONE) return null
    return { Icon(Icons.Rounded.Flag, contentDescription = priorityLabel(priority), tint = priorityTint(priority)) }
}

/** A small neutral pill showing subtask progress, e.g. "3 / 5". */
@Composable
private fun SubtaskCountChip(done: Int, total: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ListAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "$done / $total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shared priority colour so the flag reads the same in the list and the detail view. */
@Composable
internal fun priorityTint(priority: Priority): Color = when (priority) {
    Priority.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    Priority.HIGH -> MaterialTheme.colorScheme.error
    Priority.MEDIUM -> MaterialTheme.colorScheme.tertiary
    Priority.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The colored reveal behind a swiping row: complete on the left, delete on the right. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(target: SwipeToDismissBoxValue, isCompleted: Boolean) {
    val (color, icon, desc, alignment) = when (target) {
        SwipeToDismissBoxValue.StartToEnd -> SwipeStyle(
            MaterialTheme.colorScheme.primaryContainer,
            Icons.Rounded.Check,
            if (isCompleted) R.string.cd_reopen else R.string.cd_complete,
            Alignment.CenterStart,
        )
        SwipeToDismissBoxValue.EndToStart -> SwipeStyle(
            MaterialTheme.colorScheme.errorContainer,
            Icons.Rounded.Delete,
            R.string.delete,
            Alignment.CenterEnd,
        )
        SwipeToDismissBoxValue.Settled -> SwipeStyle(
            Color.Transparent, Icons.Rounded.Check, R.string.cd_complete, Alignment.CenterStart,
        )
    }
    Surface(color = color, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = alignment) {
            Icon(icon, contentDescription = stringResource(desc))
        }
    }
}

private data class SwipeStyle(
    val color: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val descRes: Int,
    val alignment: Alignment,
)

private fun cardShape(position: Position, full: androidx.compose.ui.unit.Dp, small: androidx.compose.ui.unit.Dp): Shape =
    when (position) {
        Position.Alone -> RoundedCornerShape(full)
        Position.Top -> RoundedCornerShape(topStart = full, topEnd = full, bottomStart = small, bottomEnd = small)
        Position.Middle -> RoundedCornerShape(small)
        Position.Bottom -> RoundedCornerShape(topStart = small, topEnd = small, bottomStart = full, bottomEnd = full)
    }

@Composable
private fun SectionHeader(text: String) {
    // Opaque surface so rows scroll cleanly under the pinned header.
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 8.dp),
        )
    }
}

/** A tappable section header with a count and a chevron that rotates on expand. */
@Composable
private fun CollapsibleSectionHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 28.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

/** Icon + message for an empty list — the Calendula agenda-empty pattern. */
@Composable
private fun EmptyState(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Checklist,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenteredMessage(text: String, inner: PaddingValues) {
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

@Composable
private fun titleFor(filter: TaskFilter, listName: String?): String = when (filter) {
    is TaskFilter.OfList -> listName ?: stringResource(R.string.tasks_title)
    is TaskFilter.Smart -> stringResource(
        when (filter.list) {
            SmartList.TODAY -> R.string.smart_today
            SmartList.UPCOMING -> R.string.smart_upcoming
            SmartList.OVERDUE -> R.string.smart_overdue
            SmartList.ALL -> R.string.smart_all
            SmartList.NO_DATE -> R.string.smart_no_date
            SmartList.COMPLETED -> R.string.smart_completed
        },
    )
}

@Composable
private fun sectionLabel(section: TaskSection): String = stringResource(
    when (section) {
        TaskSection.OVERDUE -> R.string.section_overdue
        TaskSection.TODAY -> R.string.section_today
        TaskSection.UPCOMING -> R.string.section_upcoming
        TaskSection.NO_DATE -> R.string.section_no_date
        TaskSection.COMPLETED -> R.string.section_completed
    },
)

@Composable
internal fun priorityLabel(priority: Priority): String = stringResource(
    when (priority) {
        Priority.NONE -> R.string.priority_none
        Priority.LOW -> R.string.priority_low
        Priority.MEDIUM -> R.string.priority_medium
        Priority.HIGH -> R.string.priority_high
    },
)

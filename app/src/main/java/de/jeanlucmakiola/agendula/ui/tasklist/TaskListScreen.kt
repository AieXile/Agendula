package de.jeanlucmakiola.agendula.ui.tasklist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.floret.time.DayWindow
import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.SmartList
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskSection
import de.jeanlucmakiola.agendula.domain.TaskSections
import de.jeanlucmakiola.agendula.ui.common.ListNameChip
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.agendula.ui.common.PriorityChip
import de.jeanlucmakiola.floret.time.formatDateTime
import de.jeanlucmakiola.floret.components.pastelize
import de.jeanlucmakiola.floret.components.positionOf
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

    // Swipe-delete hides the row at once, then a floating "Deleted · Undo" chip
    // gives an undo window: Undo restores it, otherwise the delete commits. A
    // second delete while a chip is up commits the first (its window is over).
    var undoTarget by remember { mutableStateOf<Task?>(null) }
    val onRequestDelete: (Task) -> Unit = { task ->
        undoTarget?.let { viewModel.commitDelete(it.taskId) }
        viewModel.markPendingDelete(task)
        undoTarget = task
    }
    LaunchedEffect(undoTarget) {
        val target = undoTarget ?: return@LaunchedEffect
        delay(4_000)
        viewModel.commitDelete(target.taskId)
        if (undoTarget?.taskId == target.taskId) undoTarget = null
    }

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
        Box(modifier = Modifier.fillMaxSize()) {
            when (val s = state) {
                TaskListUiState.Loading -> Unit // brief; avoids a flash before first emission
                TaskListUiState.Failure -> CenteredMessage(stringResource(R.string.task_list_failure), inner)
                is TaskListUiState.Content -> TaskListBody(s, filter, inner, onOpenTask, onRequestDelete, viewModel)
            }
            UndoChip(
                visible = undoTarget != null,
                onUndo = {
                    undoTarget?.let { viewModel.undoDelete(it.taskId) }
                    undoTarget = null
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = inner.calculateBottomPadding() + 24.dp),
            )
        }
    }
}

/**
 * A compact floating "snackchip" for an undoable delete — a rounded pill (not a
 * full-width snackbar) sized to its content, sliding up from the bottom centre.
 */
@Composable
private fun UndoChip(visible: Boolean, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(50),
            shadowElevation = 6.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.task_deleted),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = onUndo,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.undo),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
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
    onRequestDelete: (Task) -> Unit,
    viewModel: TaskListViewModel,
) {
    val (todayStart, todayEnd) = remember { DayWindow.today(Clock.System.now(), ZoneId.systemDefault()) }
    // A parent represents its children via a progress chip (counts come from the
    // repository, over the full set), so drop children from the flat list — unless
    // their parent isn't in this view (can happen in smart lists), where we keep
    // them standalone so nothing silently vanishes.
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
    // Parents the user expanded to reveal their subtasks inline. Children are
    // grouped from the same flat task set the list already holds.
    var expandedParents by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val childrenByParent = remember(state.tasks) {
        state.tasks.filter { it.isSubtask }.groupBy { it.parentId!! }
    }

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
                // Flatten the section into one grouped run: each parent, followed
                // by its subtasks when expanded. Each row's corners then come from
                // its place in the whole run (positionOf below) — so the children
                // read as a grouped list nested in the list's grouped list:
                // full-width, connecting to the parent above (small top corners),
                // and the run rounds off (full corners) only at its genuine ends.
                val rows = buildList {
                    val topLevel = section.tasks
                    topLevel.forEachIndexed { p, task ->
                        val children = childrenByParent[task.taskId].orEmpty()
                        // Only expandable when we hold every child: true in a real
                        // list, but a smart list may omit children due on other
                        // days, and a half-shown set would mislead.
                        val canExpand = task.subtaskTotal > 0 && children.size == task.subtaskTotal
                        val isExpanded = canExpand && task.taskId in expandedParents
                        // Top-level tasks keep their own connected run — full top
                        // only on the first, full bottom only on the last — so the
                        // task after an expanded one stays mid-run (small top). An
                        // expanded parent's bottom opens (small) to meet its subtasks.
                        val parentPosition = cornerPosition(
                            topFull = p == 0,
                            bottomFull = if (isExpanded) false else p == topLevel.lastIndex,
                        )
                        add(RenderRow(ListRow.Parent(task, canExpand, isExpanded), parentPosition))
                        if (isExpanded) {
                            // When the add row is hidden (M5 setting), the last subtask
                            // instead rounds the group off (full bottom).
                            val sorted = children.sortedBy { it.isCompleted }
                            sorted.forEachIndexed { c, sub ->
                                // Subtasks connect up to the parent (small top); with the
                                // add row present every subtask is mid-run, otherwise the
                                // last one closes the group (full bottom).
                                val closesGroup = !state.showAddSubtaskRow && c == sorted.lastIndex
                                add(RenderRow(ListRow.Sub(sub), cornerPosition(topFull = false, bottomFull = closesGroup)))
                            }
                            // The add row rounds the group off (full bottom),
                            // independent of whatever top-level task follows.
                            if (state.showAddSubtaskRow) {
                                add(RenderRow(ListRow.AddSub(task), cornerPosition(topFull = false, bottomFull = true)))
                            }
                        }
                    }
                }
                itemsIndexed(rows, key = { _, r -> r.row.key }) { index, r ->
                    val lastInRun = index == rows.lastIndex
                    when (val row = r.row) {
                        is ListRow.Parent -> TaskRow(
                            task = row.task,
                            position = r.position,
                            lastInRun = lastInRun,
                            showListName = showListName,
                            subtaskDone = row.task.subtaskDone,
                            subtaskTotal = row.task.subtaskTotal,
                            expanded = row.expanded,
                            onToggleExpand = if (row.expandable) {
                                {
                                    expandedParents = if (row.task.taskId in expandedParents) {
                                        expandedParents - row.task.taskId
                                    } else {
                                        expandedParents + row.task.taskId
                                    }
                                }
                            } else {
                                null
                            },
                            onToggle = { viewModel.toggleComplete(row.task) },
                            onDelete = { onRequestDelete(row.task) },
                            onClick = { onOpenTask(row.task.taskId) },
                        )
                        is ListRow.Sub -> SubtaskRow(
                            task = row.task,
                            position = r.position,
                            lastInRun = lastInRun,
                            onToggle = { viewModel.toggleComplete(row.task) },
                            onClick = { onOpenTask(row.task.taskId) },
                        )
                        is ListRow.AddSub -> AddSubtaskRow(
                            position = r.position,
                            lastInRun = lastInRun,
                            onAdd = { viewModel.quickAddSubtask(row.parent, it) },
                        )
                    }
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
    lastInRun: Boolean,
    showListName: Boolean,
    subtaskDone: Int,
    subtaskTotal: Int,
    expanded: Boolean,
    onToggleExpand: (() -> Unit)?,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                // Both snap back: complete toggles status; delete hands off to the
                // undo flow, which removes the row from the data. Never *confirm*
                // the dismiss — a terminal dismissed state strands a stale
                // background bar and won't re-arm when the row is restored.
                SwipeToDismissBoxValue.StartToEnd -> { onToggle(); false }
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    // A small gap below every row but the run's last — including a group-ending
    // Bottom that's followed by a fresh card, so the two groups read apart.
    val gap = if (lastInRun) Modifier else Modifier.padding(bottom = 2.dp)

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(gap),
        // dismissDirection (not targetValue) tracks the live drag, so the colour +
        // icon reveal as you swipe rather than only once the threshold is crossed.
        backgroundContent = { SwipeBackground(dismissState.dismissDirection, task.isCompleted) },
    ) {
        TaskRowContent(task, position, showListName, subtaskDone, subtaskTotal, expanded, onToggleExpand, onToggle, onClick)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskRowContent(
    task: Task,
    position: Position,
    showListName: Boolean,
    subtaskDone: Int,
    subtaskTotal: Int,
    expanded: Boolean,
    onToggleExpand: (() -> Unit)?,
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
    val hasSupporting = due != null || listName != null || subtaskTotal > 0 ||
        task.priority != Priority.NONE

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
                    // Flow so the chips wrap to a further line when due date + list
                    // + subtask progress can't share one (e.g. a dated task with
                    // subtasks), rather than squeezing the trailing chip.
                    FlowRow(
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (task.priority != Priority.NONE) {
                            PriorityChip(task.priority, priorityLabel(task.priority))
                        }
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
            // Trailing area is only ever the expand control, so the row's right
            // edge stays put whether or not the task has children (priority now
            // rides in the supporting chip row instead of a shifting trailing flag).
            if (onToggleExpand != null) {
                SubtaskExpandButton(expanded = expanded, onToggle = onToggleExpand)
            }
        }
    }
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

/**
 * The inline "add a subtask" row that closes an expanded group — a [BasicTextField]
 * aligned with the subtask titles (leading + where the checkbox sits), submitting
 * on the tick or the IME action. Same grouped-card segment as the rows around it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSubtaskRow(
    position: Position,
    lastInRun: Boolean,
    onAdd: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text.trim())
            text = ""
        }
    }
    val gap = if (lastInRun) Modifier else Modifier.padding(bottom = 2.dp)
    Surface(
        shape = cardShape(position, 22.dp, 6.dp),
        // Same tone as the subtask rows it closes, so the group reads as one unit.
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(gap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(5.dp)) // the parents' colour-bar slot, kept for alignment
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.add_subtask_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
            if (text.isNotBlank()) {
                IconButton(onClick = ::submit) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.cd_add_task),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** The trailing expand/collapse control for a parent row — a rotating chevron. */
@Composable
private fun SubtaskExpandButton(expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "subtaskExpand")
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.cd_collapse_subtasks else R.string.cd_expand_subtasks,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(rotation),
        )
    }
}

/** A visual row in a flattened section run: a top-level task, or one of its subtasks. */
private sealed interface ListRow {
    val key: Long

    data class Parent(val task: Task, val expandable: Boolean, val expanded: Boolean) : ListRow {
        override val key: Long get() = task.taskId
    }

    data class Sub(val task: Task) : ListRow {
        override val key: Long get() = task.taskId
    }

    /** The inline "add a subtask" row that closes an expanded group. */
    data class AddSub(val parent: Task) : ListRow {
        // Negative so it never collides with a real (positive) provider task id.
        override val key: Long get() = -parent.taskId
    }
}

/** A flattened row paired with the grouped-card corners it should render with. */
private data class RenderRow(val row: ListRow, val position: Position)

/**
 * Map independent top/bottom corner choices to a [Position]. `full` = the big
 * outer corner radius (a run's start/end); otherwise the small connecting radius.
 * Letting each edge be chosen on its own lets a subtask group round off (full
 * bottom) while the next top-level task keeps a small top and stays mid-run.
 */
private fun cornerPosition(topFull: Boolean, bottomFull: Boolean): Position = when {
    topFull && bottomFull -> Position.Alone
    topFull -> Position.Top
    bottomFull -> Position.Bottom
    else -> Position.Middle
}

/**
 * One subtask row inside an expanded parent: a full-width grouped-card segment
 * (same tonal surface, corners-from-[position], press-morph as the task rows), so
 * it's part of the same grouped run. The only difference is a leading indent
 * before the checkbox to convey nesting — the card width is preserved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtaskRow(
    task: Task,
    position: Position,
    lastInRun: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val full by animateDpAsState(if (pressed) 36.dp else 22.dp, label = "subFullCorner")
    val small by animateDpAsState(if (pressed) 36.dp else 6.dp, label = "subSmallCorner")
    val gap = if (lastInRun) Modifier else Modifier.padding(bottom = 2.dp)
    Surface(
        onClick = onClick,
        shape = cardShape(position, full, small),
        // A step below the parents' surfaceContainerHigh so the subtask group
        // reads as a distinct, recessive tone — the nesting cue alongside shape.
        color = MaterialTheme.colorScheme.surfaceContainer,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(gap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No colour bar — that absence (with the grouped nesting) marks the row
            // as a subtask. Reserve the bar's 5dp so the checkbox still lines up
            // with the parent rows' checkboxes rather than indenting.
            Spacer(Modifier.width(5.dp))
            Checkbox(checked = task.isCompleted, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(4.dp))
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
                modifier = Modifier.weight(1f),
            )
        }
    }
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

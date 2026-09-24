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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.ui.res.pluralStringResource
import de.jeanlucmakiola.agendula.domain.TaskSortOrder
import de.jeanlucmakiola.floret.components.InlineTextField
import de.jeanlucmakiola.floret.components.OptionPicker
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalFocusManager
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.floret.time.DayWindow
import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.SmartList
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskSection
import de.jeanlucmakiola.agendula.domain.TaskSections
import de.jeanlucmakiola.agendula.ui.common.LocalUse24HourFormat
import de.jeanlucmakiola.agendula.ui.common.priorityAccent
import de.jeanlucmakiola.agendula.ui.lists.ListEditorSheet
import de.jeanlucmakiola.agendula.ui.lists.listWriteFailureMessage
import de.jeanlucmakiola.agendula.ui.lists.ListWriteFailure
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.SnackChip
import de.jeanlucmakiola.floret.components.SnackChipHeight
import de.jeanlucmakiola.floret.components.SnackChipMargin
import de.jeanlucmakiola.floret.time.formatDateTimeCompact
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
    onOpenTask: (Task) -> Unit,
    onNewTask: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val content = state as? TaskListUiState.Content
    val list = content?.list
    val listName = list?.name
    val listId = (filter as? TaskFilter.OfList)?.listId
    // Editing is offered for a device-only list. A collection that belongs to an
    // account is the server's to rename or remove, not ours.
    var editingList by rememberSaveable { mutableStateOf(false) }
    val listWriteFailure by viewModel.listWriteFailure.collectAsStateWithLifecycle()
    val listDeleted by viewModel.listDeleted.collectAsStateWithLifecycle()
    // The list this screen is about is gone; there is nothing left to show.
    LaunchedEffect(listDeleted) { if (listDeleted) onBack() }
    LaunchedEffect(listWriteFailure) {
        if (listWriteFailure != null) {
            delay(4_000)
            viewModel.clearListWriteFailure()
        }
    }
    // One add affordance, never two: a real list with the setting on gets a pinned
    // bottom quick-add bar; everything else (incl. smart lists, which have no single
    // target list) gets the floating "New task" button.
    val showBottomAddBar = content?.bottomAddBar == true && listId != null

    // Swipe-delete and completing a task both leave a floating undo chip; the
    // view model owns the window, so leaving the screen commits instead of losing it.
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val onRequestDelete: (Task) -> Unit = viewModel::requestDelete
    var showSort by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

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
                actions = {
                    IconButton(onClick = { showSort = true }, enabled = content != null) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.sort_title))
                    }
                    if (list != null && list.isLocal) {
                        IconButton(onClick = { editingList = true }) {
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = stringResource(R.string.list_edit_title),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (!showBottomAddBar) {
                ExtendedFloatingActionButton(
                    onClick = onNewTask,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_task)) },
                )
            }
        },
        bottomBar = {
            if (showBottomAddBar) {
                QuickAddBar(onAdd = { title -> viewModel.quickAdd(title, listId) })
            }
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (val s = state) {
                TaskListUiState.Loading -> Unit // brief; avoids a flash before first emission
                TaskListUiState.Failure -> CenteredMessage(stringResource(R.string.task_list_failure), inner)
                is TaskListUiState.Content -> TaskListBody(
                    s, filter, inner, onOpenTask, onRequestDelete, viewModel,
                    onClearCompleted = if (listId != null && list?.isReadOnly != true) ({ confirmClear = true }) else null,
                )
            }
            // A FAB-height band anchored at the bottom-start with the FAB's own
            // margin; centring the chip in it lines the chip up beside the bottom-end
            // FAB at exactly its height, rather than sitting a touch above it.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = SnackChipMargin,
                        bottom = inner.calculateBottomPadding() + SnackChipMargin,
                    )
                    .height(SnackChipHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                // One chip, one anchor: the undo receipt takes precedence, and a
                // refused list write reports itself once the undo window is clear.
                val failure = listWriteFailure
                if (undo != null || failure == null) {
                    SnackChip(
                        visible = undo != null,
                        message = stringResource(
                            if (undo is UndoReceipt.Completed) R.string.task_completed else R.string.task_deleted,
                        ),
                        actionLabel = stringResource(R.string.undo),
                        onAction = viewModel::undo,
                    )
                } else {
                    SnackChip(
                        visible = true,
                        message = stringResource(listWriteFailureMessage(failure)),
                    )
                }
            }
        }
    }

    if (showSort && content != null) {
        OptionPicker(
            title = stringResource(R.string.sort_title),
            options = TaskSortOrder.entries,
            selected = content.sortOrder,
            label = { stringResource(sortLabel(it)) },
            onSelect = { viewModel.setSortOrder(it); showSort = false },
            onDismiss = { showSort = false },
        )
    }

    if (confirmClear && listId != null) {
        val count = content?.tasks?.count { it.isCompleted } ?: 0
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_completed_title)) },
            text = { Text(pluralStringResource(R.plurals.clear_completed_body, count, count)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; viewModel.clearCompleted(listId) }) {
                    Text(stringResource(R.string.clear_completed_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    if (editingList && list != null) {
        ListEditorSheet(
            initial = list,
            onSave = { name, color, _ -> viewModel.updateList(list.id, name, color, list.accountId) },
            onDismiss = { editingList = false },
            hiddenFromSmartLists = content?.hiddenFromSmartLists ?: false,
            onHiddenFromSmartListsChange = { viewModel.setHiddenFromSmartLists(list.id, it) },
            // ⚠️ Absent for a read-only share, whose removal belongs to whoever
            // owns it — and which the server would refuse anyway. Present for
            // the account's own collections, where it now deletes on the server
            // as well as here.
            onDelete = if (list.isReadOnly) {
                null
            } else {
                { viewModel.deleteList(list.id, list.accountId) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TaskListBody(
    state: TaskListUiState.Content,
    filter: TaskFilter,
    inner: PaddingValues,
    onOpenTask: (Task) -> Unit,
    onRequestDelete: (Task) -> Unit,
    viewModel: TaskListViewModel,
    onClearCompleted: (() -> Unit)? = null,
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
    // In a smart list a parent's subtasks are usually filtered out (they're undated
    // or due other days), so we pull them on demand. Report only expanded parents
    // whose full child set isn't already in this view; the VM then observes those
    // children live. In a real list that set is empty, so no extra queries run.
    val tasksById = remember(state.tasks) { state.tasks.associateBy { it.taskId } }
    val parentsNeedingChildren = expandedParents.filter { id ->
        val parent = tasksById[id]
        parent != null && (childrenByParent[id]?.size ?: 0) != parent.subtaskTotal
    }.toSet()
    LaunchedEffect(parentsNeedingChildren) { viewModel.setLazyChildParents(parentsNeedingChildren) }
    val lazyChildren by viewModel.lazyChildren.collectAsStateWithLifecycle()
    // A parent's full child set: the rows already in this view when we hold them
    // all, otherwise the lazily-loaded set fetched for smart lists.
    fun childrenOf(task: Task): List<Task> {
        val inList = childrenByParent[task.taskId].orEmpty()
        return if (inList.size == task.subtaskTotal) inList else lazyChildren[task.taskId].orEmpty()
    }

    // When the bottom quick-add bar is shown it already lives in the scaffold's
    // bottomBar (so `inner` covers it); otherwise reserve room to clear the FAB.
    val bottomBarShown = state.bottomAddBar && listId != null
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = inner.calculateTopPadding() + 8.dp,
            bottom = inner.calculateBottomPadding() + if (bottomBarShown) 16.dp else 96.dp,
        ),
    ) {
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
                            onClear = onClearCompleted,
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
                        // The full child set — already in this view for a real list,
                        // or fetched on demand for a smart list (childrenOf). A parent
                        // is expandable whenever it has children; until a smart list's
                        // set has loaded the group simply shows nothing extra.
                        val children = childrenOf(task)
                        val canExpand = task.subtaskTotal > 0
                        val isExpanded = canExpand && task.taskId in expandedParents
                        // No quick-add into a read-only share: it would never upload.
                        val showAdd = state.showAddSubtaskRow && !task.isReadOnly
                        val hasExpansionContent = children.isNotEmpty() || showAdd
                        // Top-level tasks keep their own connected run — full top
                        // only on the first, full bottom only on the last — so the
                        // task after an expanded one stays mid-run (small top). An
                        // expanded parent's bottom opens (small) to meet its subtasks.
                        val parentPosition = cornerPosition(
                            topFull = p == 0,
                            bottomFull = if (isExpanded && hasExpansionContent) false else p == topLevel.lastIndex,
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
                                val closesGroup = !showAdd && c == sorted.lastIndex
                                add(RenderRow(ListRow.Sub(sub), cornerPosition(topFull = false, bottomFull = closesGroup)))
                            }
                            // The add row rounds the group off (full bottom),
                            // independent of whatever top-level task follows.
                            if (showAdd) {
                                add(RenderRow(ListRow.AddSub(task), cornerPosition(topFull = false, bottomFull = true)))
                            }
                        }
                    }
                }
                itemsIndexed(rows, key = { _, r -> r.row.key }) { index, r ->
                    val lastInRun = index == rows.lastIndex
                    // animateItem so an (un)expanding group fades its subtasks in/out
                    // and slides the rows below — clean whether the children were
                    // already loaded (real list) or arrive a frame later (smart list).
                    val rowModifier = Modifier.animateItem()
                    when (val row = r.row) {
                        is ListRow.Parent -> TaskRow(
                            modifier = rowModifier,
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
                            onClick = { onOpenTask(row.task) },
                        )
                        is ListRow.Sub -> SubtaskRow(
                            modifier = rowModifier,
                            task = row.task,
                            position = r.position,
                            lastInRun = lastInRun,
                            onToggle = { viewModel.toggleComplete(row.task) },
                            onDelete = { onRequestDelete(row.task) },
                            onClick = { onOpenTask(row.task) },
                        )
                        is ListRow.AddSub -> AddSubtaskRow(
                            modifier = rowModifier,
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

/**
 * Inline "add a task" — title only, into the current list. Shared with the
 * onboarding step that previews the bottom bar this sits in.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun InlineAdd(onAdd: (String) -> Unit, elevated: Boolean = false) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text.trim())
            text = ""
        }
    }
    // Compose keeps a TextField focused (cursor blinking) even after the keyboard
    // is dismissed by a tap outside it — so when the IME hides, drop focus too.
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (!imeVisible) focusManager.clearFocus()
    }
    Surface(
        shape = RoundedCornerShape(22.dp),
        // Floating use (bottom quick-add) steps up to the brightest container tone
        // — a real step above the surfaceContainerHigh task cards — and lifts off
        // the list with a heavier shadow, so it doesn't blend into the rows.
        color = if (elevated) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shadowElevation = if (elevated) 8.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                InlineTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = stringResource(R.string.add_task_hint),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    imeAction = ImeAction.Done,
                    onImeAction = ::submit,
                )
            }
            if (text.isNotBlank()) {
                IconButton(onClick = ::submit) {
                    Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.cd_add_task))
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

/**
 * The bottom quick-add field: [InlineAdd] pinned as the scaffold's bottomBar, but
 * with no bar plate of its own — the rounded field floats on the screen background
 * (its own shadow lifting it off the list). Rides above the keyboard and clears the
 * navigation bar (ime ∪ navigation-bar insets).
 */
@Composable
private fun QuickAddBar(onAdd: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        InlineAdd(onAdd = onAdd, elevated = true)
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
    modifier: Modifier = Modifier,
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
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).then(gap),
        // dismissDirection (not targetValue) tracks the live drag, so the colour +
        // icon reveal as you swipe rather than only once the threshold is crossed.
        backgroundContent = { SwipeBackground(dismissState.dismissDirection, task.isCompleted) },
        // A read-only share takes neither swipe: both are writes the server refuses.
        gesturesEnabled = !task.isReadOnly,
    ) {
        TaskRowContent(task, position, showListName, subtaskDone, subtaskTotal, expanded, onToggleExpand, onToggle, onClick)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TaskRowContent(
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
    val due = task.due?.formatDateTimeCompact(task.isAllDay, LocalUse24HourFormat.current)
    val listName = task.listName?.takeIf { showListName && it.isNotBlank() }
    // Everything secondary collapses into one quiet supporting line below the title.
    val hasMeta = task.priority != Priority.NONE || due != null || subtaskTotal > 0 ||
        listName != null || task.isOccurrence

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
            Checkbox(
                checked = task.isCompleted,
                onCheckedChange = { onToggle() },
                enabled = !task.isReadOnly,
            )
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
                if (hasMeta) {
                    Spacer(Modifier.height(3.dp))
                    TaskMetaLine(
                        priority = task.priority,
                        due = due,
                        recurring = task.isOccurrence,
                        subtaskDone = subtaskDone,
                        subtaskTotal = subtaskTotal,
                        listName = listName,
                        listColor = task.effectiveColor,
                        dark = dark,
                    )
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

/**
 * The single low-emphasis supporting line under a task title: priority, due date,
 * subtask progress and (in mixed views) the list name, joined by middots. The text
 * is quiet `onSurfaceVariant` throughout; the only colour is the priority flag and
 * the list name (tinted to its list) — so even a fully-populated row stays calm and
 * one line instead of a stack of filled pills.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskMetaLine(
    priority: Priority,
    due: String?,
    recurring: Boolean,
    subtaskDone: Int,
    subtaskTotal: Int,
    listName: String?,
    listColor: Int,
    dark: Boolean,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodySmall
    val segments = buildList<@Composable () -> Unit> {
        if (priority != Priority.NONE) {
            add {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        Icons.Rounded.Flag,
                        contentDescription = null,
                        tint = priorityAccent(priority, dark),
                        modifier = Modifier.size(13.dp),
                    )
                    Text(priorityLabel(priority), style = style, color = muted)
                }
            }
        }
        if (due != null || recurring) {
            add {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (recurring) {
                        Icon(
                            Icons.Rounded.Repeat,
                            contentDescription = stringResource(R.string.cd_repeats),
                            tint = muted,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    if (due != null) Text(due, style = style, color = muted)
                }
            }
        }
        if (subtaskTotal > 0) {
            add {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ListAlt,
                        contentDescription = null,
                        tint = muted,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(stringResource(R.string.subtask_progress, subtaskDone, subtaskTotal), style = style, color = muted)
                }
            }
        }
        if (listName != null) {
            add {
                Text(
                    listName,
                    style = style,
                    color = pastelize(listColor, dark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    FlowRow(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { i, segment ->
            // A middot between segments — its own flow item, so the line can still
            // wrap gracefully when truly everything is set on a narrow screen.
            if (i > 0) {
                Text("·", style = style, color = muted, modifier = Modifier.padding(horizontal = 6.dp))
            }
            segment()
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
    modifier: Modifier = Modifier,
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
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).then(gap),
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
    val key: String

    data class Parent(val task: Task, val expandable: Boolean, val expanded: Boolean) : ListRow {
        override val key: String get() = task.occurrenceKey
    }

    data class Sub(val task: Task) : ListRow {
        override val key: String get() = task.occurrenceKey
    }

    /** The inline "add a subtask" row that closes an expanded group. */
    data class AddSub(val parent: Task) : ListRow {
        // Prefixed so it never collides with the task row it belongs to.
        override val key: String get() = "add-${parent.occurrenceKey}"
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
    onDelete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onToggle(); false }
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val full by animateDpAsState(if (pressed) 36.dp else 22.dp, label = "subFullCorner")
    val small by animateDpAsState(if (pressed) 36.dp else 6.dp, label = "subSmallCorner")
    val gap = if (lastInRun) Modifier else Modifier.padding(bottom = 2.dp)
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).then(gap),
        backgroundContent = { SwipeBackground(dismissState.dismissDirection, task.isCompleted) },
    ) {
    Surface(
        onClick = onClick,
        shape = cardShape(position, full, small),
        // A step below the parents' surfaceContainerHigh so the subtask group
        // reads as a distinct, recessive tone — the nesting cue alongside shape.
        color = MaterialTheme.colorScheme.surfaceContainer,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth(),
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
            Checkbox(
                checked = task.isCompleted,
                onCheckedChange = { onToggle() },
                enabled = !task.isReadOnly,
            )
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
    onClear: (() -> Unit)? = null,
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
            if (onClear != null) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.clear_completed_action)) }
            }
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

private fun sortLabel(order: TaskSortOrder): Int = when (order) {
    TaskSortOrder.DUE -> R.string.sort_due
    TaskSortOrder.PRIORITY -> R.string.sort_priority
    TaskSortOrder.TITLE -> R.string.sort_title_alpha
    TaskSortOrder.CREATED -> R.string.sort_created
}

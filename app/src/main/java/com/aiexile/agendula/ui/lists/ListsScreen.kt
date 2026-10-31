package com.aiexile.agendula.ui.lists

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiexile.agendula.R
import com.aiexile.agendula.domain.SmartList
import com.aiexile.agendula.domain.Task
import com.aiexile.agendula.domain.TaskFilter
import com.aiexile.agendula.ui.common.ListColorChip
import com.aiexile.agendula.ui.common.ShapedActionButton
import com.aiexile.agendula.ui.common.SnackChipWithShadow
import com.aiexile.agendula.ui.common.accountLabel
import com.aiexile.agendula.ui.tasklist.TaskRowContent
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.ReorderableColumn
import de.jeanlucmakiola.floret.components.SnackChipHeight
import de.jeanlucmakiola.floret.components.SnackChipMargin
import de.jeanlucmakiola.floret.components.positionOf
import kotlinx.coroutines.delay

/**
 * Home: the open tasks in a capped feed of their own, then the user's lists
 * grouped by account, their tags and their saved filters. The feed's header opens
 * the All smart list, a row opens its task, and the FAB starts a new one. The
 * search action in the top bar slides open into a full-width field that filters
 * every task by title.
 */
@Composable
fun ListsScreen(
    onOpenFilter: (TaskFilter) -> Unit,
    onOpenTask: (Task) -> Unit,
    onNewTask: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onNewFilter: () -> Unit = {},
    viewModel: ListsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var newList by rememberSaveable { mutableStateOf(false) }
    val destinations by viewModel.destinations.collectAsStateWithLifecycle()
    // ⚠️ Asked when the sheet opens, not when the screen is built. The answer is
    // an OPTIONS against each account's home set, and an account added since —
    // or a server upgraded since — has to be able to appear without a restart.
    val deviceLabel = stringResource(R.string.list_where_device)
    LaunchedEffect(newList) { if (newList) viewModel.refreshDestinations(deviceLabel) }
    val closeSearch = {
        query = ""
        searchActive = false
    }
    // With no lists there is nowhere to put a task, so the primary action becomes
    // making one — otherwise a fresh install's FAB opens a form that cannot save.
    val noLists = (state as? ListsUiState.Content)?.groups?.isEmpty() == true
    // The sheet closes on save, so a refused write has to report itself here.
    val writeFailure by viewModel.writeFailure.collectAsStateWithLifecycle()
    LaunchedEffect(writeFailure) {
        if (writeFailure != null) {
            delay(4_000)
            viewModel.clearWriteFailure()
        }
    }
    // System back closes search before leaving the screen.
    BackHandler(enabled = searchActive, onBack = closeSearch)

    Scaffold(
        modifier = modifier,
        topBar = {
            HomeTopBar(
                searchActive = searchActive,
                query = query,
                onQueryChange = { query = it },
                onToggleSearch = { if (searchActive) closeSearch() else searchActive = true },
                onOpenSettings = onOpenSettings,
            )
        },
        floatingActionButton = {
            // The FAB would otherwise float over the search results.
            if (!searchActive) {
                ExtendedFloatingActionButton(
                    onClick = if (noLists) ({ newList = true }) else onNewTask,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = {
                        Text(stringResource(if (noLists) R.string.list_add else R.string.new_task))
                    },
                )
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
            when (val s = state) {
                ListsUiState.Loading -> Unit // brief; avoids a flash before first emission
                ListsUiState.Failure ->
                    CenteredMessage(stringResource(R.string.lists_failure), PaddingValues(0.dp))
                is ListsUiState.Content -> {
                    ListsContent(
                        state = s,
                        onOpenFilter = onOpenFilter,
                        onOpenTask = onOpenTask,
                        onToggleTask = viewModel::toggleComplete,
                        onNewList = { newList = true },
                        onNewFilter = onNewFilter,
                        onReorder = viewModel::reorderLists,
                        topPadding = 0.dp,
                        bottomPadding = inner.calculateBottomPadding() + 96.dp,
                    )
                    // While searching with a non-blank query, results cover the home
                    // content; an empty query leaves the home content visible behind
                    // the open field.
                    if (searchActive) {
                        SearchResults(
                            query = query,
                            allTasks = s.allTasks,
                            onOpenTask = { taskId ->
                                closeSearch()
                                onOpenTask(taskId)
                            },
                            onToggleTask = viewModel::toggleComplete,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            // Anchored beside the FAB, at its height — the same receipt placement
            // the task list uses.
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
                SnackChipWithShadow(
                    visible = writeFailure != null,
                    message = stringResource(
                        listWriteFailureMessage(writeFailure ?: ListWriteFailure.SAVE),
                    ),
                )
            }
        }
    }

    if (newList) {
        ListEditorSheet(
            initial = null,
            onSave = viewModel::createList,
            onDismiss = { newList = false },
            destinations = destinations,
        )
    }
}

/**
 * Wording for a refused list write.
 *
 * Shared with the task list, which offers the same edit from the other side —
 * one refusal should not read differently depending on where it was asked for.
 */
internal fun listWriteFailureMessage(failure: ListWriteFailure): Int = when (failure) {
    ListWriteFailure.SAVE -> R.string.list_save_failed
    ListWriteFailure.DELETE -> R.string.list_delete_failed
    ListWriteFailure.SERVER_REFUSED -> R.string.list_save_failed_server
    ListWriteFailure.OFFLINE -> R.string.list_save_failed_offline
    ListWriteFailure.READ_ONLY -> R.string.list_save_failed_read_only
    ListWriteFailure.UNSUPPORTED -> R.string.list_save_failed_unsupported
}

@Composable
private fun ListsContent(
    state: ListsUiState.Content,
    onOpenFilter: (TaskFilter) -> Unit,
    onOpenTask: (Task) -> Unit,
    onToggleTask: (Task) -> Unit,
    onNewList: () -> Unit,
    onNewFilter: () -> Unit,
    onReorder: (List<Long>) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    // Lists get drag handles only while reordering, so the home screen stays calm.
    var reordering by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = topPadding,
            bottom = bottomPadding,
        ),
    ) {
        // The open tasks themselves, in a capped region of their own; the header
        // in it leads to the full list. This replaced the Today hero and the
        // Overdue/All tiles.
        item(key = "task-feed") {
            HomeTaskFeed(
                tasks = state.openTasks,
                onOpenAll = { onOpenFilter(TaskFilter.Smart(SmartList.ALL)) },
                onOpenTask = onOpenTask,
                onToggle = onToggleTask,
            )
        }

        if (state.groups.isEmpty()) {
            item { EmptyLists(onNewList = onNewList) }
        } else {
            item {
                SectionHeader(
                    text = stringResource(R.string.lists_header),
                    action = {
                        IconButton(onClick = { reordering = !reordering }) {
                            Icon(
                                if (reordering) Icons.Rounded.Check else Icons.Rounded.SwapVert,
                                contentDescription = stringResource(
                                    if (reordering) R.string.lists_reorder_done else R.string.lists_reorder,
                                ),
                            )
                        }
                        ShapedActionButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.list_add),
                            onClick = onNewList,
                        )
                    },
                )
            }
            state.groups.forEach { group ->
                item(key = "acct-${group.accountName}") {
                    AccountHeader(accountLabel(group.accountName, group.isLocal))
                }
                if (reordering) {
                    item(key = "reorder-${group.accountName}") {
                        ReorderableColumn(
                            items = group.lists,
                            keyOf = { it.list.id },
                            onReorder = { ordered -> onReorder(ordered.map { it.list.id }) },
                            rowHeight = 72.dp,
                        ) { overview, position, dragHandle, isDragging ->
                            GroupedRow(
                                title = overview.list.name,
                                position = position,
                                minHeight = 72.dp,
                                gapBelow = false,
                                container = if (isDragging) MaterialTheme.colorScheme.secondaryContainer else null,
                                leading = { ListColorChip(overview.list.color) },
                                trailing = {
                                    Box(modifier = dragHandle.size(48.dp), contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Rounded.DragHandle,
                                            contentDescription = stringResource(R.string.lists_reorder_handle),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                            )
                        }
                    }
                    return@forEach
                }
                itemsIndexed(group.lists, key = { _, o -> o.list.id }) { index, overview ->
                    GroupedRow(
                        title = overview.list.name,
                        position = positionOf(index, group.lists.size),
                        minHeight = 72.dp,
                        leading = { ListColorChip(overview.list.color) },
                        trailing = {
                            if (overview.openCount > 0) {
                                Text(
                                    overview.openCount.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { onOpenFilter(TaskFilter.OfList(overview.list.id)) },
                    )
                }
            }
        }

        if (state.tags.isNotEmpty()) {
            item(key = "tags-header") { SectionHeader(stringResource(R.string.tags_header)) }
            itemsIndexed(state.tags, key = { _, t -> "tag-${t.tag}" }) { index, overview ->
                GroupedRow(
                    title = overview.tag,
                    position = positionOf(index, state.tags.size),
                    leading = {
                        Icon(
                            Icons.Rounded.Sell,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailing = {
                        Text(
                            overview.openCount.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { onOpenFilter(TaskFilter.Tagged(overview.tag)) },
                )
            }
        }

        if (state.groups.isNotEmpty()) {
            filtersSection(state.filters, onOpenFilter, onNewFilter)
        }
    }
}

/** The saved filters, or a single row offering the first one. */
private fun LazyListScope.filtersSection(
    filters: List<FilterOverview>,
    onOpenFilter: (TaskFilter) -> Unit,
    onNewFilter: () -> Unit,
) {
    item(key = "filters-header") {
        SectionHeader(
            text = stringResource(R.string.filters_header),
            action = if (filters.isEmpty()) {
                null
            } else {
                {
                    IconButton(onClick = onNewFilter) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.filter_new_title))
                    }
                }
            },
        )
    }
    if (filters.isEmpty()) {
        item(key = "filters-empty") {
            GroupedRow(
                title = stringResource(R.string.filter_new_title),
                summary = stringResource(R.string.filters_empty_hint),
                position = Position.Alone,
                leading = { Icon(Icons.Rounded.Add, contentDescription = null) },
                onClick = onNewFilter,
            )
        }
        return
    }
    itemsIndexed(filters, key = { _, f -> "filter-${f.filter.id}" }) { index, overview ->
        GroupedRow(
            title = overview.filter.name,
            position = positionOf(index, filters.size),
            leading = {
                Icon(
                    Icons.Rounded.FilterList,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailing = {
                Text(
                    overview.openCount.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = { onOpenFilter(TaskFilter.Saved(overview.filter.id)) },
        )
    }
}

/** No lists at all — a fresh install, where nothing else on this screen works yet. */
@Composable
private fun EmptyLists(onNewList: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.lists_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onNewList) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.lists_empty_action))
        }
    }
}

/**
 * The home top bar. There is no title — the launcher icon already says which app
 * this is. Settings is pinned at the right; the search action sits just left of it
 * and stays put. Tapping search unfurls a pill to its left (the field grows out
 * from the icon's side) while the search icon remains as the bar's fixed trailing
 * icon, so neither action moves.
 */
@Composable
private fun HomeTopBar(
    searchActive: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(72.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Search occupies the flexible space left of settings. Pulled into its
            // own composable so AnimatedVisibility resolves to the plain overload —
            // a RowScope receiver in here would make that call ambiguous.
            SearchSlot(
                searchActive = searchActive,
                query = query,
                onQueryChange = onQueryChange,
                onToggleSearch = onToggleSearch,
                modifier = Modifier.weight(1f),
            )
            ShapedActionButton(
                icon = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.settings_title),
                onClick = onOpenSettings,
                modifier = Modifier.padding(start = 8.dp),
                size = 48.dp,
                iconSize = 26.dp,
            )
        }
    }
}

/**
 * The search action and the field it unfurls. The magnifier is pinned to the end
 * (right) of this slot and never moves; tapping it toggles search. When active a
 * pill expands leftward from it ([expandHorizontally] anchored at the end) holding
 * the query field, so the icon reads as the bar's fixed trailing icon.
 */
@Composable
private fun SearchSlot(
    searchActive: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            visible = searchActive,
            enter = expandHorizontally(tween(300), expandFrom = Alignment.End) + fadeIn(tween(280)),
            exit = shrinkHorizontally(tween(220), shrinkTowards = Alignment.End) + fadeOut(tween(140)),
        ) {
            SearchPill(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Always on top, at the end — the bar's fixed search icon and toggle.
        ShapedActionButton(
            icon = Icons.Rounded.Search,
            contentDescription = stringResource(R.string.home_search_hint),
            onClick = onToggleSearch,
            size = 48.dp,
            iconSize = 26.dp,
        )
    }
}

/**
 * The expanding search input pill: the query field (auto-focused on open) and a
 * clear button once there is text. Its trailing 48dp is left empty for the search
 * icon that [SearchSlot] overlays at the end.
 */
@Composable
private fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(52.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                decorationBox = { innerField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.home_search_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.home_search_clear),
                    )
                }
            }
            // Space reserved for the search icon SearchSlot overlays at the end.
            Spacer(Modifier.width(48.dp))
        }
    }
}

/**
 * Search results overlaying the home content: tasks whose title matches the query,
 * across every list (completed included), open ones first. A blank query renders
 * nothing so the home content shows through behind the open field.
 */
@Composable
private fun SearchResults(
    query: String,
    allTasks: List<Task>,
    onOpenTask: (Task) -> Unit,
    onToggleTask: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (query.isBlank()) return
    val results = remember(query, allTasks) {
        val q = query.trim()
        allTasks
            .filter { t -> listOfNotNull(t.title, t.description, t.location).any { it.contains(q, ignoreCase = true) } }
            // A series matches once, on its current occurrence.
            .sortedBy { kotlin.math.abs(it.distanceFromCurrent ?: 0) }
            .distinctBy { it.seriesId ?: it.taskId }
            .sortedWith(compareBy({ it.isCompleted }, { it.title.lowercase() }))
    }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    text = stringResource(R.string.home_search_empty, query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // The feed's row, so a hit looks like the row it came from.
                itemsIndexed(results, key = { _, task -> task.occurrenceKey }) { index, task ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                    ) {
                        TaskRowContent(
                            task = task,
                            position = positionOf(index, results.size),
                            showListName = true,
                            subtaskDone = task.subtaskDone,
                            subtaskTotal = task.subtaskTotal,
                            expanded = false,
                            onToggleExpand = null,
                            onToggle = { onToggleTask(task) },
                            onClick = { onOpenTask(task) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 28.dp,
                end = if (action == null) 28.dp else 20.dp,
                top = if (action == null) 16.dp else 12.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
private fun AccountHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 4.dp),
    )
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

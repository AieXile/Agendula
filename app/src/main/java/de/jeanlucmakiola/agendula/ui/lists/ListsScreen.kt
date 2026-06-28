package de.jeanlucmakiola.agendula.ui.lists

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Upcoming
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.SmartList
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.ui.common.ActionShapes
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.agendula.ui.common.ListColorChip
import de.jeanlucmakiola.agendula.ui.common.ShapedActionButton
import de.jeanlucmakiola.agendula.ui.common.priorityAccent
import de.jeanlucmakiola.agendula.ui.tasklist.priorityLabel
import de.jeanlucmakiola.floret.components.positionOf
import de.jeanlucmakiola.floret.time.formatDateTimeCompact
import java.time.LocalDate
import java.time.ZoneId

/**
 * Home: a Today progress hero (wavy ring over tasks due today), an Overdue + All
 * 2-up of tonal tiles, a live preview of the next upcoming tasks, then the user's
 * lists grouped by account. Tapping a tile opens that smart list, an upcoming row
 * opens that task, and the FAB starts a new task. The search action in the top bar
 * slides open into a full-width field that filters every task by title.
 */
@Composable
fun ListsScreen(
    onOpenFilter: (TaskFilter) -> Unit,
    onOpenTask: (Long) -> Unit,
    onNewTask: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ListsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val closeSearch = {
        query = ""
        searchActive = false
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
                    onClick = onNewTask,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_task)) },
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
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListsContent(
    state: ListsUiState.Content,
    onOpenFilter: (TaskFilter) -> Unit,
    onOpenTask: (Long) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = topPadding,
            bottom = bottomPadding,
        ),
    ) {
        item {
            TodayHero(
                done = state.todayDone,
                total = state.todayTotal,
                onClick = { onOpenFilter(TaskFilter.Smart(SmartList.TODAY)) },
            )
        }
        // Overdue + All sit below the Today hero as a quieter 2-up; Upcoming is no
        // longer a count tile — it becomes the live preview further down.
        item {
            SmartPairRow(
                counts = state.smartCounts.filter {
                    it.smart == SmartList.OVERDUE || it.smart == SmartList.ALL
                },
                onOpenFilter = onOpenFilter,
            )
        }

        if (state.upcoming.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.smart_upcoming)) }
            item {
                UpcomingPreview(
                    tasks = state.upcoming,
                    onOpenTask = onOpenTask,
                    onViewAll = { onOpenFilter(TaskFilter.Smart(SmartList.UPCOMING)) },
                )
            }
        }

        if (state.groups.isEmpty()) {
            item { CenteredMessage(stringResource(R.string.lists_empty), PaddingValues(top = 24.dp)) }
        } else {
            item { SectionHeader(stringResource(R.string.lists_header)) }
            state.groups.forEach { group ->
                item(key = "acct-${group.accountName}") { AccountHeader(group.accountName) }
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
                shape = ActionShapes.Settings,
                icon = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.settings_title),
                onClick = onOpenSettings,
                modifier = Modifier.padding(start = 8.dp),
                size = 48.dp,
                iconSize = 26.dp,
                spinIcon = true,
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
            shape = ActionShapes.Search,
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
    onOpenTask: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (query.isBlank()) return
    val results = remember(query, allTasks) {
        val q = query.trim()
        allTasks
            .filter { it.title.contains(q, ignoreCase = true) }
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
                items(results, key = { it.id }) { task ->
                    UpcomingRow(task = task, onClick = { onOpenTask(task.taskId) })
                }
            }
        }
    }
}

/**
 * The day's momentum: a wavy progress ring over "x of y done" for tasks due today.
 * Tapping opens the Today list. When nothing is due today it drops the ring and
 * reads as a calm, finished state rather than an empty 0.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TodayHero(done: Int, total: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 34.dp else 22.dp, label = "todayCorner")
    val left = total - done
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(140.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Today, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.smart_today), style = MaterialTheme.typography.titleMedium)
                }
                val headline = when {
                    total == 0 -> stringResource(R.string.home_today_empty)
                    left == 0 -> stringResource(R.string.home_today_all_done)
                    else -> stringResource(R.string.home_today_progress, done, total)
                }
                Text(headline, style = MaterialTheme.typography.headlineSmall)
                if (total > 0 && left > 0) {
                    Text(
                        stringResource(R.string.home_today_remaining, left),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
            if (total > 0) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
                    CircularWavyProgressIndicator(
                        progress = { done.toFloat() / total },
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.22f),
                    )
                    Text("$done/$total", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Overdue + All as a 2-up row of the existing tonal tiles. */
@Composable
private fun SmartPairRow(counts: List<SmartCount>, onOpenFilter: (TaskFilter) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        counts.forEach { smart ->
            SmartCard(
                count = smart,
                modifier = Modifier.weight(1f),
                onClick = { onOpenFilter(TaskFilter.Smart(smart.smart)) },
            )
        }
        if (counts.size == 1) Spacer(Modifier.weight(1f))
    }
}

/** A grouped card previewing the next few upcoming tasks, with a "view all" tail. */
@Composable
private fun UpcomingPreview(
    tasks: List<Task>,
    onOpenTask: (Long) -> Unit,
    onViewAll: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column {
            tasks.forEach { task ->
                UpcomingRow(task = task, onClick = { onOpenTask(task.taskId) })
            }
            Surface(onClick = onViewAll, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.home_upcoming_view_all),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * One slim upcoming row: list-colour avatar, title, then a quiet meta line of the
 * relative due date and (if set) a tinted priority flag — the same calm one-line
 * treatment as the task list, minus the swipe machinery.
 */
@Composable
private fun UpcomingRow(task: Task, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val metaStyle = MaterialTheme.typography.bodySmall
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ListColorChip(task.effectiveColor)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    upcomingDueLabel(task)?.let { Text(it, style = metaStyle, color = muted) }
                    if (task.priority != Priority.NONE) {
                        Text("·", style = metaStyle, color = muted)
                        Icon(
                            Icons.Rounded.Flag,
                            contentDescription = null,
                            tint = priorityAccent(task.priority, dark),
                            modifier = Modifier.size(13.dp),
                        )
                        Text(priorityLabel(task.priority), style = metaStyle, color = muted)
                    }
                }
            }
        }
    }
}

/** "Today" / "Tomorrow" for the near dates, else the compact date. */
@Composable
private fun upcomingDueLabel(task: Task): String? {
    val due = task.due ?: return null
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val dueDate = remember(due) {
        java.time.Instant.ofEpochMilli(due.toEpochMilliseconds()).atZone(zone).toLocalDate()
    }
    return when (dueDate) {
        today -> stringResource(R.string.home_due_today)
        today.plusDays(1) -> stringResource(R.string.home_due_tomorrow)
        else -> due.formatDateTimeCompact(task.isAllDay)
    }
}

private data class SmartStyle(val icon: ImageVector, val labelRes: Int, val container: Color, val onContainer: Color)

@Composable
private fun smartStyle(smart: SmartList): SmartStyle {
    val colors = MaterialTheme.colorScheme
    return when (smart) {
        SmartList.TODAY -> SmartStyle(Icons.Rounded.Today, R.string.smart_today, colors.primaryContainer, colors.onPrimaryContainer)
        SmartList.OVERDUE -> SmartStyle(Icons.Rounded.ErrorOutline, R.string.smart_overdue, colors.errorContainer, colors.onErrorContainer)
        SmartList.UPCOMING -> SmartStyle(Icons.Rounded.Upcoming, R.string.smart_upcoming, colors.tertiaryContainer, colors.onTertiaryContainer)
        else -> SmartStyle(Icons.AutoMirrored.Rounded.ListAlt, R.string.smart_all, colors.secondaryContainer, colors.onSecondaryContainer)
    }
}

/**
 * Clean tonal tile. The expressive touch is subtle: rounded corners gently morph
 * rounder on press (the same quiet shape behaviour as the lists), not a loud
 * silhouette.
 */
@Composable
private fun SmartCard(count: SmartCount, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val style = smartStyle(count.smart)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 34.dp else 22.dp, label = "smartCorner")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = style.container,
        contentColor = style.onContainer,
        interactionSource = interaction,
        modifier = modifier.height(116.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(style.icon, contentDescription = null)
            Column {
                Text(count.count.toString(), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(style.labelRes), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 4.dp),
    )
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

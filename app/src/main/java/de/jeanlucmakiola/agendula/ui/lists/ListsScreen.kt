package de.jeanlucmakiola.agendula.ui.lists

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Upcoming
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.domain.SmartList
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.ui.common.ActionShapes
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.agendula.ui.common.ListColorChip
import de.jeanlucmakiola.agendula.ui.common.ShapedActionButton
import de.jeanlucmakiola.floret.components.positionOf

/**
 * Home: smart lists (Today / Overdue / Upcoming / All) as tonal cards with live
 * counts, then the user's lists grouped by account. Tapping a card or row opens
 * that task list; the FAB starts a new task.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    onOpenFilter: (TaskFilter) -> Unit,
    onNewTask: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ListsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    ShapedActionButton(
                        shape = ActionShapes.Settings,
                        icon = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.settings_title),
                        onClick = onOpenSettings,
                        modifier = Modifier.padding(end = 8.dp),
                        size = 48.dp,
                        iconSize = 26.dp,
                    )
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
            ListsUiState.Loading -> Unit // brief; avoids a flash before first emission
            ListsUiState.Failure -> CenteredMessage(stringResource(R.string.lists_failure), inner)
            is ListsUiState.Content -> ListsContent(s, inner, onOpenFilter)
        }
    }
}

@Composable
private fun ListsContent(
    state: ListsUiState.Content,
    inner: PaddingValues,
    onOpenFilter: (TaskFilter) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = inner.calculateTopPadding(),
            bottom = inner.calculateBottomPadding() + 96.dp,
        ),
    ) {
        item { SmartGrid(state.smartCounts, onOpenFilter) }

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

@Composable
private fun SmartGrid(counts: List<SmartCount>, onOpenFilter: (TaskFilter) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        counts.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { smart ->
                    SmartCard(
                        count = smart,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenFilter(TaskFilter.Smart(smart.smart)) },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
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

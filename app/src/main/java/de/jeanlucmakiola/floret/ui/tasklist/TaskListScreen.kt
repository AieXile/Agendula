package de.jeanlucmakiola.floret.ui.tasklist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.floret.R
import de.jeanlucmakiola.floret.domain.SmartList
import de.jeanlucmakiola.floret.domain.Task
import de.jeanlucmakiola.floret.domain.TaskFilter

/**
 * The tasks for one [TaskFilter]. **Base scaffold** — renders the rows, toggle,
 * and open/new navigation against the already-built [TaskListViewModel]. The
 * richer M2 UI (swipe-to-complete / -delete, inline add field, smart-list
 * section headers) layers on top of this.
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(titleFor(filter)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
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
            is TaskListUiState.Content ->
                if (s.tasks.isEmpty()) {
                    CenteredMessage(stringResource(R.string.task_list_empty), inner)
                } else {
                    LazyColumn(contentPadding = inner) {
                        items(s.tasks, key = { it.taskId }) { task ->
                            TaskRow(
                                task = task,
                                onToggle = { viewModel.toggleComplete(task) },
                                onClick = { onOpenTask(task.taskId) },
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun TaskRow(task: Task, onToggle: () -> Unit, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Checkbox(checked = task.isCompleted, onCheckedChange = { onToggle() }) },
        headlineContent = {
            Text(task.title.ifBlank { stringResource(R.string.task_untitled) })
        },
        supportingContent = task.description?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
    )
}

@Composable
private fun CenteredMessage(text: String, inner: androidx.compose.foundation.layout.PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text) }
}

@Composable
private fun titleFor(filter: TaskFilter): String = when (filter) {
    is TaskFilter.OfList -> stringResource(R.string.tasks_title)
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

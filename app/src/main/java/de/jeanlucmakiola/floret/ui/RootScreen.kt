package de.jeanlucmakiola.floret.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.floret.R
import de.jeanlucmakiola.floret.data.tasks.ProviderStatus
import de.jeanlucmakiola.floret.domain.SmartList
import de.jeanlucmakiola.floret.domain.Task
import de.jeanlucmakiola.floret.domain.TaskFilter
import de.jeanlucmakiola.floret.ui.lists.ListsUiState
import de.jeanlucmakiola.floret.ui.lists.ListsViewModel
import de.jeanlucmakiola.floret.ui.permission.PermissionViewModel
import de.jeanlucmakiola.floret.ui.tasklist.TaskListUiState
import de.jeanlucmakiola.floret.ui.tasklist.TaskListViewModel

/**
 * ⚠️ Functional scaffold, not final UI. It proves the data layer end to end
 * (provider gating → live lists/tasks → toggle/add) and is the canvas to replace
 * screen-by-screen with the real Material 3 Expressive design (see docs/PLAN.md
 * §4): ListsScreen, TaskListScreen, TaskDetail/Edit, Settings. The ViewModels it
 * uses are the finished, render-only state holders those screens will consume.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(
    modifier: Modifier = Modifier,
    permissionViewModel: PermissionViewModel = hiltViewModel(),
) {
    val permission by permissionViewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionViewModel.refresh() }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            when (permission.status) {
                ProviderStatus.NO_PROVIDER -> Gate(
                    title = stringResource(R.string.onboarding_no_provider_title),
                    body = stringResource(R.string.onboarding_no_provider_body),
                )
                ProviderStatus.NEEDS_PERMISSION -> Gate(
                    title = stringResource(R.string.onboarding_permission_title),
                    body = stringResource(R.string.onboarding_permission_body),
                    action = stringResource(R.string.onboarding_permission_button),
                    onAction = { launcher.launch(permission.permissionsToRequest.toTypedArray()) },
                )
                ProviderStatus.READY -> TasksContent()
            }
        }
    }
}

@Composable
private fun Gate(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium)
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun TasksContent(
    listsViewModel: ListsViewModel = hiltViewModel(),
    taskListViewModel: TaskListViewModel = hiltViewModel(),
) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        taskListViewModel.bind(TaskFilter.Smart(SmartList.ALL))
    }
    val lists by listsViewModel.state.collectAsStateWithLifecycle()
    val tasks by taskListViewModel.state.collectAsStateWithLifecycle()

    val firstListId = (lists as? ListsUiState.Content)
        ?.groups?.firstOrNull()?.lists?.firstOrNull()?.list?.id

    var newTitle by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Lists") }
        when (val l = lists) {
            ListsUiState.Loading -> item { ListItem(headlineContent = { Text("Loading…") }) }
            ListsUiState.Failure -> item { ListItem(headlineContent = { Text("Could not read tasks.") }) }
            is ListsUiState.Content -> {
                items(l.smartCounts) { sc ->
                    ListItem(
                        headlineContent = { Text(sc.smart.name) },
                        trailingContent = { Text(sc.count.toString()) },
                    )
                }
                l.groups.forEach { group ->
                    item { SectionHeader(group.accountName) }
                    items(group.lists) { overview ->
                        ListItem(
                            headlineContent = { Text(overview.list.name) },
                            trailingContent = { Text(overview.openCount.toString()) },
                        )
                    }
                }
            }
        }

        item { HorizontalDivider() }
        item { SectionHeader("All open tasks") }
        when (val t = tasks) {
            TaskListUiState.Loading -> item { ListItem(headlineContent = { Text("Loading…") }) }
            TaskListUiState.Failure -> item { ListItem(headlineContent = { Text("Could not read tasks.") }) }
            is TaskListUiState.Content -> items(t.tasks) { task ->
                TaskRow(task = task, onToggle = { taskListViewModel.toggleComplete(task) })
            }
        }

        if (firstListId != null) {
            item {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("New task") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            item {
                Button(
                    onClick = {
                        taskListViewModel.quickAdd(newTitle, firstListId)
                        newTitle = ""
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("Add") }
            }
        }
    }
}

@Composable
private fun TaskRow(task: Task, onToggle: () -> Unit) {
    ListItem(
        leadingContent = { Checkbox(checked = task.isCompleted, onCheckedChange = { onToggle() }) },
        headlineContent = {
            Text(
                task.title.ifBlank { stringResource(R.string.task_untitled) },
                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
            )
        },
        supportingContent = task.listName?.let { { Text(it) } },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

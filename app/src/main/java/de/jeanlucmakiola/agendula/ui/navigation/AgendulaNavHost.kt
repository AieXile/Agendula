package de.jeanlucmakiola.agendula.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jeanlucmakiola.agendula.domain.Task
import kotlin.time.Instant
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.ui.detail.TaskDetailScreen
import de.jeanlucmakiola.agendula.ui.detail.TaskDetailViewModel
import de.jeanlucmakiola.agendula.ui.edit.TaskEditScreen
import de.jeanlucmakiola.agendula.ui.edit.TaskEditViewModel
import de.jeanlucmakiola.agendula.ui.imports.ImportScreen
import de.jeanlucmakiola.agendula.ui.lists.ListsScreen
import de.jeanlucmakiola.agendula.ui.settings.SettingsScreen
import de.jeanlucmakiola.agendula.ui.tasklist.TaskListScreen
import de.jeanlucmakiola.agendula.ui.tasklist.TaskListViewModel

/**
 * The single in-app back stack: lists → task list → detail / edit.
 *
 * Each destination resolves its own Hilt [androidx.lifecycle.ViewModel] and
 * feeds it the route arguments through that VM's `bind(...)` method (the VMs
 * were built render-only in M1 and take their input this way rather than from a
 * `SavedStateHandle`). Screens receive plain navigation lambdas so they stay
 * unaware of the nav library.
 */
@Composable
fun AgendulaNavHost(
    modifier: Modifier = Modifier,
    navRequest: NavRequest? = null,
    onNavRequestConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()

    LaunchedEffect(navRequest) {
        when (navRequest) {
            null -> return@LaunchedEffect
            is NavRequest.OpenTask ->
                nav.navigate(Dest.TaskDetail.build(navRequest.taskId, navRequest.occurrenceStart))
            NavRequest.OpenAccounts ->
                nav.navigate(Dest.Settings.build(Dest.Settings.SECTION_ACCOUNTS)) { launchSingleTop = true }
            is NavRequest.OpenAccount ->
                nav.navigate(Dest.Settings.buildAccount(navRequest.accountId)) { launchSingleTop = true }
            is NavRequest.NewTask -> nav.navigate(Dest.TaskEdit.buildNew(title = navRequest.title))
            is NavRequest.OpenSmart -> nav.navigate(Dest.TaskList.build(TaskFilter.Smart(navRequest.list))) {
                launchSingleTop = true
            }
            is NavRequest.OpenList -> nav.navigate(Dest.TaskList.build(TaskFilter.OfList(navRequest.listId))) {
                launchSingleTop = true
            }
            is NavRequest.Import -> nav.navigate(Dest.Import.build(navRequest.uri))
        }
        onNavRequestConsumed()
    }

    NavHost(
        navController = nav,
        startDestination = Dest.LISTS,
        modifier = modifier,
        // Only the screen on top moves; the background is always held static.
        // Forward is a plain fade-in over the held screen — calm, not a slide.
        // Back is the predictive-back peek: navigation-compose composes the
        // screen being revealed underneath and seeks popExit with the gesture, so
        // the leaving screen scales down to reveal the (unmoved) previous screen.
        enterTransition = { fadeIn() },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { scaleOut(targetScale = 0.9f) + fadeOut() },
    ) {
        composable(Dest.LISTS) {
            ReadableWidth {
                ListsScreen(
                    onOpenFilter = { filter -> nav.navigate(Dest.TaskList.build(filter)) },
                    onOpenTask = { task -> nav.navigate(task.detailRoute()) },
                    onNewTask = { nav.navigate(Dest.TaskEdit.buildNew()) },
                    onOpenSettings = { nav.navigate(Dest.SETTINGS) },
                )
            }
        }

        composable(Dest.Settings.route, arguments = Dest.Settings.arguments) { entry ->
            ReadableWidth {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    initialSection = entry.arguments?.getString(Dest.Settings.ARG_SECTION),
                    initialAccountId = entry.arguments?.getLong(Dest.Settings.ARG_ACCOUNT)?.takeIf { it != Dest.NO_ID },
                )
            }
        }

        composable(Dest.Import.route, arguments = Dest.Import.arguments) { entry ->
            ReadableWidth {
                ImportScreen(
                    onBack = { nav.popBackStack() },
                    initialUri = entry.arguments?.getString(Dest.Import.ARG_URI)?.let(android.net.Uri::parse),
                )
            }
        }

        composable(Dest.TaskList.route, arguments = Dest.TaskList.arguments) { entry ->
            val args = entry.arguments
            val filter = Dest.TaskList.filterOf(
                smart = args?.getString(Dest.TaskList.ARG_SMART),
                listId = args?.getLong(Dest.TaskList.ARG_LIST_ID) ?: Dest.NO_ID,
            )
            val vm: TaskListViewModel = hiltViewModel()
            LaunchedEffect(filter) { vm.bind(filter) }

            val taskList: @Composable (onOpenTask: (Task) -> Unit) -> Unit = { onOpenTask ->
                TaskListScreen(
                    filter = filter,
                    viewModel = vm,
                    onOpenTask = onOpenTask,
                    onNewTask = {
                        val presetListId = (filter as? TaskFilter.OfList)?.listId
                        nav.navigate(Dest.TaskEdit.buildNew(presetListId = presetListId))
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            BoxWithConstraints {
                if (maxWidth >= LIST_DETAIL_MIN_WIDTH) {
                    TaskListDetailPanes(list = taskList, onEdit = { task -> nav.navigate(task.editRoute()) })
                } else {
                    ReadableWidth { taskList { task -> nav.navigate(task.detailRoute()) } }
                }
            }
        }

        composable(Dest.TaskDetail.route, arguments = Dest.TaskDetail.arguments) { entry ->
            val taskId = entry.arguments?.getLong(Dest.TaskDetail.ARG_TASK_ID) ?: Dest.NO_ID
            val occurrence = entry.arguments?.getLong(Dest.TaskDetail.ARG_OCC)?.takeIf { it != Dest.NO_ID }
            val vm: TaskDetailViewModel = hiltViewModel()
            LaunchedEffect(taskId, occurrence) { vm.bind(taskId, occurrence?.let(Instant::fromEpochMilliseconds)) }

            ReadableWidth {
                TaskDetailScreen(
                    viewModel = vm,
                    onEdit = { task -> nav.navigate(task.editRoute()) },
                    onDeleted = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                    // A subtask opens its own detail — another entry on the stack, with
                    // its own VM bound to that id (it may have children of its own).
                    onOpenTask = { subtask -> nav.navigate(subtask.detailRoute()) },
                    onDuplicated = { id -> nav.navigate(Dest.TaskDetail.build(id)) },
                )
            }
        }

        composable(Dest.TaskEdit.route, arguments = Dest.TaskEdit.arguments) { entry ->
            val args = entry.arguments
            val taskId = args?.getLong(Dest.TaskEdit.ARG_TASK_ID) ?: Dest.NO_ID
            val presetListId = args?.getLong(Dest.TaskEdit.ARG_PRESET_LIST_ID) ?: Dest.NO_ID
            val parentId = args?.getLong(Dest.TaskEdit.ARG_PARENT_ID) ?: Dest.NO_ID
            val occurrence = args?.getLong(Dest.TaskEdit.ARG_OCCURRENCE)?.takeIf { it != Dest.NO_ID }
            val title = args?.getString(Dest.TaskEdit.ARG_TITLE)
            val vm: TaskEditViewModel = hiltViewModel()
            LaunchedEffect(taskId, presetListId, parentId) {
                if (taskId != Dest.NO_ID) {
                    vm.bindEdit(taskId, occurrence?.let(Instant::fromEpochMilliseconds))
                } else {
                    vm.bindNew(
                        presetListId = presetListId.takeIf { it != Dest.NO_ID },
                        parentId = parentId.takeIf { it != Dest.NO_ID },
                        initialTitle = title,
                    )
                }
            }

            ReadableWidth {
                TaskEditScreen(
                    viewModel = vm,
                    onSaved = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

/** A task's detail route, pinned to its occurrence when it is one of a series. */
private fun Task.detailRoute(): String = Dest.TaskDetail.build(taskId, occurrenceStart?.toEpochMilliseconds())

private fun Task.editRoute(): String = Dest.TaskEdit.buildEdit(taskId, occurrenceStart?.toEpochMilliseconds())

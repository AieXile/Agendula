package de.jeanlucmakiola.agendula.ui.navigation

import de.jeanlucmakiola.floret.identity.rememberNavSlideSpec
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.ui.detail.TaskDetailScreen
import de.jeanlucmakiola.agendula.ui.detail.TaskDetailViewModel
import de.jeanlucmakiola.agendula.ui.edit.TaskEditScreen
import de.jeanlucmakiola.agendula.ui.edit.TaskEditViewModel
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
fun AgendulaNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val slide = rememberNavSlideSpec()

    NavHost(
        navController = nav,
        startDestination = Dest.LISTS,
        modifier = modifier,
        // Only the screen on top moves; the background is always held static.
        // Forward is a plain fade-in over the held screen — calm, not a slide.
        // Back (and the predictive-back drag, which drives popExit) is the snappy
        // slide-out to the right, revealing the held screen unmoved.
        enterTransition = { fadeIn() },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { slideOutHorizontally(slide) { it } + fadeOut() },
    ) {
        composable(Dest.LISTS) {
            ListsScreen(
                onOpenFilter = { filter -> nav.navigate(Dest.TaskList.build(filter)) },
                onNewTask = { nav.navigate(Dest.TaskEdit.buildNew()) },
                onOpenSettings = { nav.navigate(Dest.SETTINGS) },
            )
        }

        composable(Dest.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }

        composable(Dest.TaskList.route, arguments = Dest.TaskList.arguments) { entry ->
            val args = entry.arguments
            val filter = Dest.TaskList.filterOf(
                smart = args?.getString(Dest.TaskList.ARG_SMART),
                listId = args?.getLong(Dest.TaskList.ARG_LIST_ID) ?: Dest.NO_ID,
            )
            val vm: TaskListViewModel = hiltViewModel()
            LaunchedEffect(filter) { vm.bind(filter) }

            TaskListScreen(
                filter = filter,
                viewModel = vm,
                onOpenTask = { taskId -> nav.navigate(Dest.TaskDetail.build(taskId)) },
                onNewTask = {
                    val presetListId = (filter as? TaskFilter.OfList)?.listId
                    nav.navigate(Dest.TaskEdit.buildNew(presetListId = presetListId))
                },
                onBack = { nav.popBackStack() },
            )
        }

        composable(Dest.TaskDetail.route, arguments = Dest.TaskDetail.arguments) { entry ->
            val taskId = entry.arguments?.getLong(Dest.TaskDetail.ARG_TASK_ID) ?: Dest.NO_ID
            val vm: TaskDetailViewModel = hiltViewModel()
            LaunchedEffect(taskId) { vm.bind(taskId) }

            TaskDetailScreen(
                viewModel = vm,
                onEdit = { nav.navigate(Dest.TaskEdit.buildEdit(taskId)) },
                onDeleted = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
                // A subtask opens its own detail — another entry on the stack, with
                // its own VM bound to that id (it may have children of its own).
                onOpenTask = { subtaskId -> nav.navigate(Dest.TaskDetail.build(subtaskId)) },
            )
        }

        composable(Dest.TaskEdit.route, arguments = Dest.TaskEdit.arguments) { entry ->
            val args = entry.arguments
            val taskId = args?.getLong(Dest.TaskEdit.ARG_TASK_ID) ?: Dest.NO_ID
            val presetListId = args?.getLong(Dest.TaskEdit.ARG_PRESET_LIST_ID) ?: Dest.NO_ID
            val parentId = args?.getLong(Dest.TaskEdit.ARG_PARENT_ID) ?: Dest.NO_ID
            val vm: TaskEditViewModel = hiltViewModel()
            LaunchedEffect(taskId, presetListId, parentId) {
                if (taskId != Dest.NO_ID) {
                    vm.bindEdit(taskId)
                } else {
                    vm.bindNew(
                        presetListId = presetListId.takeIf { it != Dest.NO_ID },
                        parentId = parentId.takeIf { it != Dest.NO_ID },
                    )
                }
            }

            TaskEditScreen(
                viewModel = vm,
                onSaved = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }
    }
}

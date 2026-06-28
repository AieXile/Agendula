package de.jeanlucmakiola.agendula.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.floret.time.DayWindow
import de.jeanlucmakiola.agendula.domain.SmartList
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskFiltering
import de.jeanlucmakiola.agendula.domain.TaskList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Clock

data class ListOverview(val list: TaskList, val openCount: Int)
data class AccountGroup(val accountName: String, val lists: List<ListOverview>)
data class SmartCount(val smart: SmartList, val count: Int)

sealed interface ListsUiState {
    data object Loading : ListsUiState
    data object Failure : ListsUiState
    data class Content(
        val smartCounts: List<SmartCount>,
        val groups: List<AccountGroup>,
        /** Completed vs. total tasks *due today* — drives the progress ring. */
        val todayDone: Int,
        val todayTotal: Int,
        /** The next few open tasks (due tomorrow onward) for the inline preview. */
        val upcoming: List<Task>,
    ) : ListsUiState
}

private const val UPCOMING_PREVIEW = 3

/** The home overview: smart lists with live counts, then user lists by account. */
@HiltViewModel
class ListsViewModel @Inject constructor(
    repository: TasksRepository,
) : ViewModel() {

    val state: StateFlow<ListsUiState> =
        combine(
            repository.taskLists(),
            repository.tasks(TaskFilter.Smart(SmartList.ALL)),
            // Open smart lists drop completed tasks, but the Today ring needs the
            // ones already ticked off to show "x of y done", so read them too.
            repository.tasks(TaskFilter.Smart(SmartList.COMPLETED)),
        ) { lists, openTasks, completedTasks ->
            buildContent(lists, openTasks, completedTasks) as ListsUiState
        }.catch { emit(ListsUiState.Failure) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListsUiState.Loading)

    private fun buildContent(
        lists: List<TaskList>,
        openTasks: List<Task>,
        completedTasks: List<Task>,
    ): ListsUiState.Content {
        val (todayStart, todayEnd) = DayWindow.today(Clock.System.now(), ZoneId.systemDefault())
        // Count only top-level tasks: a subtask is represented by its parent (and
        // its progress chip), and an open subtask under a *completed* parent must
        // not read as an open item here. Mirrors the task list collapsing subtasks.
        val topLevel = openTasks.filter { !it.isSubtask }
        fun count(smart: SmartList) =
            topLevel.count { TaskFiltering.matches(it, TaskFilter.Smart(smart), todayStart, todayEnd) }

        val smartCounts = listOf(
            SmartCount(SmartList.TODAY, count(SmartList.TODAY)),
            SmartCount(SmartList.OVERDUE, count(SmartList.OVERDUE)),
            SmartCount(SmartList.UPCOMING, count(SmartList.UPCOMING)),
            SmartCount(SmartList.ALL, topLevel.size),
        )

        // Today ring: completed vs. total tasks *due today*. The numerator is the
        // top-level completed tasks whose due date falls in today's window; the
        // denominator adds the still-open ones (the Today smart count above).
        val openToday = count(SmartList.TODAY)
        val completedDueToday = completedTasks.count {
            !it.isSubtask && it.due != null && it.due >= todayStart && it.due < todayEnd
        }
        val todayTotal = openToday + completedDueToday

        // Upcoming preview: the next handful of open tasks due tomorrow onward,
        // already sorted by the repository's default ordering.
        val upcoming = topLevel
            .filter { TaskFiltering.matches(it, TaskFilter.Smart(SmartList.UPCOMING), todayStart, todayEnd) }
            .take(UPCOMING_PREVIEW)
        val openByList = topLevel.groupingBy { it.listId }.eachCount()
        val groups = lists
            .groupBy { it.accountName }
            .map { (account, accountLists) ->
                AccountGroup(
                    accountName = account,
                    lists = accountLists.map { ListOverview(it, openByList[it.id] ?: 0) },
                )
            }
            .sortedBy { it.accountName.lowercase() }
        return ListsUiState.Content(
            smartCounts = smartCounts,
            groups = groups,
            todayDone = completedDueToday,
            todayTotal = todayTotal,
            upcoming = upcoming,
        )
    }
}

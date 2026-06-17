package de.jeanlucmakiola.floret.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.floret.data.tasks.TasksRepository
import de.jeanlucmakiola.floret.domain.DayWindow
import de.jeanlucmakiola.floret.domain.SmartList
import de.jeanlucmakiola.floret.domain.Task
import de.jeanlucmakiola.floret.domain.TaskFilter
import de.jeanlucmakiola.floret.domain.TaskFiltering
import de.jeanlucmakiola.floret.domain.TaskList
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
    ) : ListsUiState
}

/** The home overview: smart lists with live counts, then user lists by account. */
@HiltViewModel
class ListsViewModel @Inject constructor(
    repository: TasksRepository,
) : ViewModel() {

    val state: StateFlow<ListsUiState> =
        combine(
            repository.taskLists(),
            repository.tasks(TaskFilter.Smart(SmartList.ALL)),
        ) { lists, openTasks ->
            buildContent(lists, openTasks) as ListsUiState
        }.catch { emit(ListsUiState.Failure) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListsUiState.Loading)

    private fun buildContent(lists: List<TaskList>, openTasks: List<Task>): ListsUiState.Content {
        val (todayStart, todayEnd) = DayWindow.today(Clock.System.now(), ZoneId.systemDefault())
        fun count(smart: SmartList) =
            openTasks.count { TaskFiltering.matches(it, TaskFilter.Smart(smart), todayStart, todayEnd) }

        val smartCounts = listOf(
            SmartCount(SmartList.TODAY, count(SmartList.TODAY)),
            SmartCount(SmartList.OVERDUE, count(SmartList.OVERDUE)),
            SmartCount(SmartList.UPCOMING, count(SmartList.UPCOMING)),
            SmartCount(SmartList.ALL, openTasks.size),
        )
        val openByList = openTasks.groupingBy { it.listId }.eachCount()
        val groups = lists
            .groupBy { it.accountName }
            .map { (account, accountLists) ->
                AccountGroup(
                    accountName = account,
                    lists = accountLists.map { ListOverview(it, openByList[it.id] ?: 0) },
                )
            }
            .sortedBy { it.accountName.lowercase() }
        return ListsUiState.Content(smartCounts, groups)
    }
}

package de.jeanlucmakiola.agendula.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.agendula.data.sync.RemoteListRepository
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.agendula.data.tasks.recoveringFromProviderFailure
import de.jeanlucmakiola.floret.time.DayWindow
import de.jeanlucmakiola.agendula.domain.SmartList
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskFiltering
import de.jeanlucmakiola.agendula.domain.TaskList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Clock

/**
 * What a list write failed at. The screens turn this into wording; the view
 * models stay free of resources.
 *
 * ⚠️ The four server-side entries are not decoration. A list that lives on a
 * server can fail in ways a device-only one cannot — the server refuses, the
 * network is gone, the share is read-only, the account has no MKCALENDAR — and
 * every one of them has a different thing for the user to do next. Collapsing
 * them into SAVE tells someone on a train that their server said no.
 */
enum class ListWriteFailure {
    SAVE,
    DELETE,

    /** The server understood and refused. Retrying changes nothing. */
    SERVER_REFUSED,

    /** The server could not be reached. Worth trying again later. */
    OFFLINE,

    /** A read-only share: the write belongs to whoever owns it. */
    READ_ONLY,

    /** This account cannot make collections at all — iCloud, Posteo, Google. */
    UNSUPPORTED,
}

/** Maps a remote outcome onto the wording the screens already know how to show. */
internal fun RemoteListRepository.Outcome.asFailure(local: ListWriteFailure): ListWriteFailure? =
    when (this) {
        RemoteListRepository.Outcome.Done -> null
        is RemoteListRepository.Outcome.Refused -> ListWriteFailure.SERVER_REFUSED
        RemoteListRepository.Outcome.Unreachable -> ListWriteFailure.OFFLINE
        RemoteListRepository.Outcome.Unsupported -> ListWriteFailure.UNSUPPORTED
        RemoteListRepository.Outcome.ReadOnly -> ListWriteFailure.READ_ONLY
        // The account is gone, stopped or undecryptable, or the server said
        // something this call cannot place. Nothing specific to say beyond
        // "that did not save", which is what [local] is — and specifically not
        // "you are offline", which neither of them is a claim about.
        RemoteListRepository.Outcome.NoAccount,
        RemoteListRepository.Outcome.Unexpected,
        -> local
    }

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
        /** Every task (open and completed) — the corpus the home search filters. */
        val allTasks: List<Task>,
    ) : ListsUiState
}

private const val UPCOMING_PREVIEW = 3

/** The home overview: smart lists with live counts, then user lists by account. */
@HiltViewModel
class ListsViewModel @Inject constructor(
    private val repository: TasksRepository,
    private val remoteLists: RemoteListRepository,
    settingsPrefs: SettingsPrefs,
) : ViewModel() {

    /**
     * Where a new list may go: the device, then every account whose server said
     * it would take one.
     *
     * ⚠️ Refreshed when the sheet opens rather than held. The answer comes from
     * an OPTIONS, and an account added — or upgraded — since this screen was
     * built has to be able to appear without a restart.
     */
    private val _destinations = MutableStateFlow(emptyList<ListDestination>())
    val destinations: StateFlow<List<ListDestination>> = _destinations.asStateFlow()

    fun refreshDestinations(deviceLabel: String) = viewModelScope.launch {
        val accounts = runCatching { remoteLists.creatableAccounts() }.getOrDefault(emptyList())
        _destinations.value = listOf(ListDestination(null, deviceLabel)) +
            accounts.map { ListDestination(it.id, it.displayName) }
    }

    val state: StateFlow<ListsUiState> =
        combine(
            repository.taskLists(),
            repository.tasks(TaskFilter.Smart(SmartList.ALL)),
            // Open smart lists drop completed tasks, but the Today ring needs the
            // ones already ticked off to show "x of y done", so read them too.
            repository.tasks(TaskFilter.Smart(SmartList.COMPLETED)),
            settingsPrefs.settings.map { it.hiddenFromSmartLists }.distinctUntilChanged(),
        ) { lists, openTasks, completedTasks, hidden ->
            buildContent(lists, openTasks, completedTasks, hidden) as ListsUiState
        }.recoveringFromProviderFailure { ListsUiState.Failure }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListsUiState.Loading)

    private fun buildContent(
        lists: List<TaskList>,
        openTasks: List<Task>,
        completedTasks: List<Task>,
        hidden: Set<Long>,
    ): ListsUiState.Content {
        val (todayStart, todayEnd) = DayWindow.today(Clock.System.now(), ZoneId.systemDefault())
        // Count only top-level tasks: a subtask is represented by its parent (and
        // its progress chip), and an open subtask under a *completed* parent must
        // not read as an open item here. Mirrors the task list collapsing subtasks.
        val topLevel = openTasks.filter { !it.isSubtask }
        // The smart counts leave out the hidden lists; each list's own count does not.
        val smartTopLevel = topLevel.filter { it.listId !in hidden }
        fun count(smart: SmartList) =
            smartTopLevel.count { TaskFiltering.matches(it, TaskFilter.Smart(smart), todayStart, todayEnd) }

        val smartCounts = listOf(
            SmartCount(SmartList.TODAY, count(SmartList.TODAY)),
            SmartCount(SmartList.OVERDUE, count(SmartList.OVERDUE)),
            SmartCount(SmartList.UPCOMING, count(SmartList.UPCOMING)),
            SmartCount(SmartList.ALL, smartTopLevel.size),
        )

        // Today ring: completed vs. total tasks *due today*. The numerator is the
        // top-level completed tasks whose due date falls in today's window; the
        // denominator adds the still-open ones (the Today smart count above).
        val openToday = count(SmartList.TODAY)
        val completedDueToday = completedTasks.count {
            !it.isSubtask && it.listId !in hidden && it.due != null && it.due >= todayStart && it.due < todayEnd
        }
        val todayTotal = openToday + completedDueToday

        // Upcoming preview: the next handful of open tasks due tomorrow onward,
        // already sorted by the repository's default ordering.
        val upcoming = smartTopLevel
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
            allTasks = openTasks + completedTasks,
        )
    }

    private val _writeFailure = MutableStateFlow<ListWriteFailure?>(null)

    /** Set when a list write is refused; the screen shows it and clears it. */
    val writeFailure: StateFlow<ListWriteFailure?> = _writeFailure.asStateFlow()

    fun clearWriteFailure() { _writeFailure.value = null }

    fun reorderLists(listIds: List<Long>) = viewModelScope.launch {
        runCatching { repository.reorderLists(listIds) }
    }

    /**
     * Create a list, on the device or on a server.
     *
     * The lists flow picks it up on the store change; a refusal — External mode,
     * a provider that says no, a server that answers 403 — surfaces through
     * [writeFailure] rather than vanishing, because the sheet has already closed.
     */
    fun createList(name: String, color: Int, accountId: Long?) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        if (accountId == null) {
            runCatching { repository.createLocalList(name.trim(), color) }
                .onFailure { _writeFailure.value = ListWriteFailure.SAVE }
            return@launch
        }
        // ⚠️ Server first. A row written before the MKCALENDAR would be a list
        // that exists on the phone and nowhere else, with nothing to say so.
        val outcome = runCatching { remoteLists.create(accountId, name.trim(), color) }
            .getOrElse { RemoteListRepository.Outcome.Unreachable }
        _writeFailure.value = outcome.asFailure(ListWriteFailure.SAVE)
    }
}

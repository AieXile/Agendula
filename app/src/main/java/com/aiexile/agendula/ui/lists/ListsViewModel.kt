package com.aiexile.agendula.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.aiexile.agendula.data.prefs.SettingsPrefs
import com.aiexile.agendula.data.sync.RemoteLists
import com.aiexile.agendula.data.tasks.TasksRepository
import com.aiexile.agendula.data.tasks.recoveringFromProviderFailure
import de.jeanlucmakiola.floret.time.DayWindow
import com.aiexile.agendula.domain.SavedFilter
import com.aiexile.agendula.domain.SmartList
import com.aiexile.agendula.domain.Task
import com.aiexile.agendula.domain.TaskFilter
import com.aiexile.agendula.domain.TaskList
import com.aiexile.agendula.domain.tagsInUse
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
internal fun RemoteLists.Outcome.asFailure(local: ListWriteFailure): ListWriteFailure? =
    when (this) {
        RemoteLists.Outcome.Done -> null
        is RemoteLists.Outcome.Refused -> ListWriteFailure.SERVER_REFUSED
        RemoteLists.Outcome.Unreachable -> ListWriteFailure.OFFLINE
        RemoteLists.Outcome.Unsupported -> ListWriteFailure.UNSUPPORTED
        RemoteLists.Outcome.ReadOnly -> ListWriteFailure.READ_ONLY
        // The account is gone, stopped or undecryptable, or the server said
        // something this call cannot place. Nothing specific to say beyond
        // "that did not save", which is what [local] is — and specifically not
        // "you are offline", which neither of them is a claim about.
        RemoteLists.Outcome.NoAccount,
        RemoteLists.Outcome.Unexpected,
        -> local
    }

data class ListOverview(val list: TaskList, val openCount: Int)
data class AccountGroup(
    val accountName: String,
    /** The device-only store's group, whose name the UI localizes. */
    val isLocal: Boolean,
    val lists: List<ListOverview>,
)
data class TagOverview(val tag: String, val openCount: Int)
data class FilterOverview(val filter: SavedFilter, val openCount: Int)

sealed interface ListsUiState {
    data object Loading : ListsUiState
    data object Failure : ListsUiState
    data class Content(
        val groups: List<AccountGroup>,
        /**
         * Every open top-level task across the visible lists, in the repository's
         * default order — the corpus behind the home task feed.
         */
        val openTasks: List<Task>,
        /** Every task (open and completed) — the corpus the home search filters. */
        val allTasks: List<Task>,
        /** Each tag on an open task, alphabetically. */
        val tags: List<TagOverview> = emptyList(),
        /** The saved filters, oldest first. */
        val filters: List<FilterOverview> = emptyList(),
    ) : ListsUiState
}

/** The home overview: the open-task feed, then user lists by account. */
@HiltViewModel
class ListsViewModel @Inject constructor(
    private val repository: TasksRepository,
    private val remoteLists: RemoteLists,
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
            // Open smart lists drop completed tasks, but the home search covers
            // them and a tag's count folds a closed subtask under its parent, so
            // read them too.
            repository.tasks(TaskFilter.Smart(SmartList.COMPLETED)),
            settingsPrefs.settings.map { it.hiddenFromSmartLists }.distinctUntilChanged(),
            settingsPrefs.savedFilters,
        ) { lists, openTasks, completedTasks, hidden, filters ->
            buildContent(lists, openTasks, completedTasks, hidden, filters) as ListsUiState
        }.recoveringFromProviderFailure { ListsUiState.Failure }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListsUiState.Loading)

    private fun buildContent(
        lists: List<TaskList>,
        openTasks: List<Task>,
        completedTasks: List<Task>,
        hidden: Set<Long>,
        savedFilters: List<SavedFilter>,
    ): ListsUiState.Content {
        val (todayStart, todayEnd) = DayWindow.today(Clock.System.now(), ZoneId.systemDefault())
        // Count only top-level tasks: a subtask is represented by its parent (and
        // its progress chip), and an open subtask under a *completed* parent must
        // not read as an open item here. Mirrors the task list collapsing subtasks.
        val topLevel = openTasks.filter { !it.isSubtask }
        // The home feed leaves out the hidden lists; each list's own count does not.
        val visible = topLevel.filter { it.listId !in hidden }
        val openByList = topLevel.groupingBy { it.listId }.eachCount()
        val groups = lists
            .groupBy { it.accountName }
            .map { (account, accountLists) ->
                AccountGroup(
                    accountName = account,
                    // Every list in a group shares its account, so one flag is enough.
                    isLocal = accountLists.first().isLocal,
                    lists = accountLists.map { ListOverview(it, openByList[it.id] ?: 0) },
                )
            }
            .sortedBy { it.accountName.lowercase() }
        // Counts the open rows the tag's own screen shows: a subtask folds under a
        // parent carrying the same tag, and stands on its own otherwise.
        val allTasks = openTasks + completedTasks
        val tags = tagsInUse(openTasks).map { tag ->
            val tagged = allTasks.filter { tag in it.tags }
            val ids = tagged.mapTo(HashSet()) { it.taskId }
            TagOverview(tag, tagged.count { !it.isClosed && (!it.isSubtask || it.parentId !in ids) })
        }
        // Same folding as the tags: a subtask counts only when its parent isn't in the filter too.
        val filters = savedFilters.map { filter ->
            val matching = openTasks.filter { filter.matches(it, todayStart, todayEnd) }
            val ids = matching.mapTo(HashSet()) { it.taskId }
            FilterOverview(filter, matching.count { !it.isSubtask || it.parentId !in ids })
        }
        return ListsUiState.Content(
            filters = filters,
            groups = groups,
            openTasks = visible,
            allTasks = allTasks,
            tags = tags,
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
     * Tick a task off (or back on) from the home feed. Same semantics as the task
     * list: completing a parent completes its open subtasks. The feed has no undo
     * chip, so the row simply leaves once the store change flows back.
     */
    fun toggleComplete(task: Task) = viewModelScope.launch {
        runCatching {
            repository.setCompleted(task.taskId, task.occurrenceStart, !task.isCompleted)
        }
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
            .getOrElse { RemoteLists.Outcome.Unreachable }
        _writeFailure.value = outcome.asFailure(ListWriteFailure.SAVE)
    }
}

package de.jeanlucmakiola.agendula.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.agendula.data.di.IoDispatcher
import de.jeanlucmakiola.agendula.data.prefs.DEFAULT_SYNC_INTERVAL_MINUTES
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.agendula.data.sync.AccountRepository
import de.jeanlucmakiola.agendula.data.sync.AccountStateStore
import de.jeanlucmakiola.agendula.data.sync.SyncAvailability
import de.jeanlucmakiola.agendula.data.sync.SyncNotice
import de.jeanlucmakiola.agendula.data.sync.SyncNoticeStore
import de.jeanlucmakiola.agendula.data.sync.SyncTrigger
import de.jeanlucmakiola.agendula.data.sync.push.PushDistributors
import de.jeanlucmakiola.agendula.data.sync.push.PushRegistrar
import de.jeanlucmakiola.agendula.data.sync.push.PushStore
import de.jeanlucmakiola.agendula.data.tasks.room.AccountEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import de.jeanlucmakiola.caldav.CalDavDiscovery
import de.jeanlucmakiola.caldav.TaskCollection
import okhttp3.HttpUrl
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: AccountRepository,
    private val syncTrigger: SyncTrigger,
    private val accountState: AccountStateStore,
    private val notices: SyncNoticeStore,
    private val settings: SettingsPrefs,
    private val distributors: PushDistributors,
    private val pushRegistrar: PushRegistrar,
    pushStore: PushStore,
    availability: SyncAvailability,
    @IoDispatcher io: CoroutineDispatcher,
) : ViewModel() {

    /** Minutes between background syncs; 0 = manual only. */
    val syncIntervalMinutes: StateFlow<Int> = settings.settings.map { it.syncIntervalMinutes }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = DEFAULT_SYNC_INTERVAL_MINUTES,
    )

    /** Store the new interval and put every account on it. */
    fun setSyncInterval(minutes: Int) {
        viewModelScope.launch {
            settings.setSyncIntervalMinutes(minutes)
            repository.rescheduleAll(intervalChanged = true)
        }
    }

    /**
     * An account's synced lists, how many its server offers push for, and how
     * many actually hold a subscription.
     */
    data class ListPush(val synced: Int, val capable: Int, val subscribed: Int) {
        companion object {
            val NONE = ListPush(synced = 0, capable = 0, subscribed = 0)
        }
    }

    private val listPush: Flow<Map<Long, ListPush>> =
        combine(repository.observeSyncedLists(), pushStore.observe()) { lists, pushes ->
            lists.groupBy { it.accountId!! }.mapValues { (_, synced) ->
                ListPush(
                    synced = synced.size,
                    capable = synced.count { pushes[it.id]?.support != null },
                    subscribed = synced.count { pushes[it.id]?.subscription != null },
                )
            }
        }.distinctUntilChanged()

    /** Push as the Accounts screen shows it. */
    data class PushUi(
        val enabled: Boolean,
        val installed: List<PushDistributors.Distributor>,
        /** The distributor in use; null when push is off or none is chosen. */
        val current: PushDistributors.Distributor?,
        val lists: ListPush,
    ) {
        /** On, distributors installed, and none chosen or set as default. */
        val needsChoice: Boolean get() = enabled && current == null && installed.isNotEmpty()
    }

    private data class DistributorState(
        val enabled: Boolean,
        val installed: List<PushDistributors.Distributor>,
        val current: PushDistributors.Distributor?,
    )

    /** Distributors are installed and removed outside the app; bumped on resume. */
    private val pushRefresh = MutableStateFlow(0)

    /** Package queries only on a setting change or a refresh, and off the main thread. */
    private val distributorState: Flow<DistributorState> = combine(
        settings.settings.map { it.pushEnabled }.distinctUntilChanged(),
        pushRefresh,
    ) { enabled, _ ->
        val installed = distributors.installed()
        val current = distributors.saved().takeIf { enabled }?.let { chosen ->
            installed.firstOrNull { it.packageName == chosen }
                ?: PushDistributors.Distributor(chosen, distributors.labelOf(chosen))
        }
        DistributorState(enabled, installed, current)
    }.flowOn(io)

    val push: StateFlow<PushUi?> = combine(distributorState, listPush) { state, perAccount ->
        PushUi(
            enabled = state.enabled,
            installed = state.installed,
            current = state.current,
            lists = ListPush(
                synced = perAccount.values.sumOf { it.synced },
                capable = perAccount.values.sumOf { it.capable },
                subscribed = perAccount.values.sumOf { it.subscribed },
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = null,
    )

    /**
     * Re-reads the installed distributors. With push on and none chosen yet, a
     * newly installed one is adopted and registered first, so the row never
     * names a distributor nothing has registered with.
     */
    fun refreshPush() {
        viewModelScope.launch {
            if (settings.settings.first().pushEnabled && distributors.saved() == null) {
                runCatching { pushRegistrar.updateAll() }
            }
            pushRefresh.update { it + 1 }
        }
    }

    /** @param packageName the distributor to use, or null to turn push off. */
    fun choosePushDistributor(packageName: String?) {
        viewModelScope.launch {
            if (packageName == null) distributors.disable() else distributors.select(packageName)
            runCatching { pushRegistrar.updateAll() }
            pushRefresh.update { it + 1 }
        }
    }

    /** False in External storage mode, where accounts are kept but neither added nor synced. */
    val accountsUsable: StateFlow<Boolean> = availability.observe().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = true,
    )

    /** An account row, plus the two things the row cannot read from the entity. */
    data class AccountRow(
        val account: AccountEntity,
        val needsSignIn: Boolean,
        /**
         * A removal is under way for this account.
         *
         * The row survives it: `remove()` revokes the app password over the
         * network before it touches any store, so several seconds pass — on a
         * server that stalls, longer — during which the row sat there looking
         * completely untouched.
         */
        val removing: Boolean = false,
        /**
         * What the last syncs destroyed or gave up on, newest first.
         *
         * ⚠️ On the row rather than in a flow of its own, because it has to
         * survive the account it belongs to going away: an account is removed
         * by id, and a notice keyed to an id nothing lists any more is a line
         * of text about nothing.
         */
        val notices: List<SyncNotice> = emptyList(),
        val lists: ListPush = ListPush.NONE,
    )

    /**
     * Accounts whose removal has started and not finished.
     *
     * ⚠️ Also the double-tap guard. Nothing else stops a second Remove from
     * starting a second revocation of a credential the first one is already
     * handing back — harmless since `c2166b9`, because the sequence is
     * idempotent either way, but it spends a second network round trip and
     * leaves the user watching two things happen to one account.
     */
    private val _removing = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * `null` until the first load, so the empty state does not flash.
     *
     * ⚠️ Observed, not fetched. The sync that a tap on this screen starts
     * finishes on a background thread some seconds later, and a snapshot taken
     * when the screen opened cannot show it — the row went on saying "never
     * synced" until the user left and came back. This also carries a *background*
     * sync, and a 401 that stops an account, onto a screen already open.
     */
    val accounts: StateFlow<List<AccountRow>?> =
        combine(
            repository.observeAll(),
            accountState.observeNeedingSignIn(),
            _removing,
            notices.observeAll(),
            listPush,
        ) { accounts, stopped, removing, allNotices, perAccount ->
            val byAccount = allNotices.groupBy { it.accountId }
            accounts.map {
                AccountRow(
                    account = it,
                    needsSignIn = it.id in stopped,
                    removing = it.id in removing,
                    notices = byAccount[it.id].orEmpty(),
                    lists = perAccount[it.id] ?: ListPush.NONE,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null,
        )

    /**
     * The app's own sync trigger.
     *
     * Not `ContentResolver.requestSync`: that is gated behind
     * `hasAuthorityAccess()` at our targetSdk and returns silently when it
     * refuses, which would leave the user pressing a button that does nothing.
     */
    fun syncNow(account: AccountEntity) {
        // Expedited: the user is looking at the button. Every other trigger is
        // ordinary work, so the exhaustible per-app quota is spent here or not
        // at all.
        syncTrigger.enqueue(account.displayName, expedited = true)
    }

    /** The user has read what the last sync changed. */
    fun dismissNotices(account: AccountEntity) {
        viewModelScope.launch { notices.dismiss(account.id) }
    }

    /** Clears a quarantined task's failure count and syncs, so it is tried again. */
    fun retry(notice: SyncNotice) {
        viewModelScope.launch { repository.retryQuarantined(notice.accountId, notice.key) }
    }

    fun remove(account: AccountEntity, deleteLocalData: Boolean) {
        if (account.id in _removing.value) return
        _removing.update { it + account.id }
        // No refresh: the row disappears because the query behind `accounts`
        // re-emits.
        viewModelScope.launch {
            try {
                repository.remove(account.id, account.displayName, deleteLocalData)
            } finally {
                // ⚠️ In a `finally`, and not only on the happy path. `remove()`
                // finishes its destructive tail uncancellable, so the id would
                // otherwise be stranded in the set by the one case that reaches
                // here without completing normally — leaving a row that is gone
                // from Room but pending for ever if it ever came back.
                _removing.update { it - account.id }
            }
        }
    }

    /** The account's collections, re-read from the server for the lists picker. */
    sealed interface ListsState {
        data object Loading : ListsState

        data class Ready(
            val accountId: Long,
            val collections: List<TaskCollection>,
            val attached: Set<String>,
            val selected: Set<HttpUrl>,
        ) : ListsState {
            /** Synced lists this selection would stop syncing — the ones worth asking about. */
            val dropping: Boolean
                get() = collections.any { it.url.toString() in attached && it.url !in selected }
        }

        data object NeedsSignIn : ListsState

        data class Failed(val cause: CalDavDiscovery.Outcome.Cause?) : ListsState
    }

    private val _lists = MutableStateFlow<ListsState?>(null)

    /** Null while the picker is closed. */
    val lists: StateFlow<ListsState?> = _lists

    fun openLists(accountId: Long) {
        _lists.value = ListsState.Loading
        viewModelScope.launch {
            val loaded = when (val found = repository.collections(accountId)) {
                is AccountRepository.Collections.Found -> ListsState.Ready(
                    accountId = accountId,
                    collections = found.collections,
                    attached = found.attached,
                    selected = found.collections
                        .filter { it.url.toString() in found.attached }
                        .map { it.url }
                        .toSet(),
                )
                AccountRepository.Collections.NeedsSignIn -> ListsState.NeedsSignIn
                is AccountRepository.Collections.Failed -> ListsState.Failed(found.cause)
            }
            // Closed while it loaded: stay closed.
            if (_lists.value == ListsState.Loading) _lists.value = loaded
        }
    }

    fun toggleList(url: HttpUrl) {
        _lists.update { state ->
            if (state !is ListsState.Ready) return@update state
            val selected = if (url in state.selected) state.selected - url else state.selected + url
            state.copy(selected = selected)
        }
    }

    fun closeLists() {
        _lists.value = null
    }

    /** @param keepUnticked what happens to the tasks of lists that stop syncing. */
    fun saveLists(keepUnticked: Boolean) {
        val state = _lists.value as? ListsState.Ready ?: return
        _lists.value = null
        viewModelScope.launch {
            repository.setSyncedCollections(
                accountId = state.accountId,
                offered = state.collections,
                selected = state.selected,
                keepUnticked = keepUnticked,
            )
        }
    }

    private companion object {
        /** Survives a configuration change without re-subscribing. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

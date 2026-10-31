package com.aiexile.agendula.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.aiexile.agendula.data.di.IoDispatcher
import com.aiexile.agendula.data.prefs.Settings
import com.aiexile.agendula.data.prefs.SettingsPrefs
import com.aiexile.agendula.data.prefs.ThemeMode
import com.aiexile.agendula.data.prefs.TimeFormatPref
import com.aiexile.agendula.data.reminders.ReminderScheduler
import com.aiexile.agendula.data.reminders.TaskNotifier
import com.aiexile.agendula.data.tasks.ProviderEnvironment
import com.aiexile.agendula.data.tasks.ProviderResolver
import com.aiexile.agendula.data.tasks.StorageMode
import com.aiexile.agendula.data.tasks.TaskProvider
import com.aiexile.agendula.data.tasks.TasksRepository
import com.aiexile.agendula.data.tasks.legacy.OneShotImport
import com.aiexile.agendula.data.tasks.recoveringFromProviderFailure
import com.aiexile.agendula.data.tasks.transfer.ExternalImport
import com.aiexile.agendula.data.tasks.transfer.TransferCounts
import com.aiexile.agendula.data.tasks.transfer.TransferResult
import com.aiexile.agendula.domain.TaskFormField
import com.aiexile.agendula.domain.TaskList
import de.jeanlucmakiola.floret.reminders.ReminderLead
import de.jeanlucmakiola.floret.reminders.ReminderOverride
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

data class SettingsUiState(
    val settings: Settings = Settings(),
    val lists: List<TaskList> = emptyList(),
)

/**
 * The storage half of Settings: which store is active, and what picking the
 * other one would mean on this device.
 *
 * Kept apart from [SettingsUiState] because that one is collected for the whole
 * Activity lifetime to drive the theme, and re-probing PackageManager on every
 * theme emission would be work for nothing.
 */
data class StorageUiState(
    val mode: StorageMode,
    /** The external provider installed here, or null when there is none to pick. */
    val external: TaskProvider? = null,
    /** That provider's own app name, for a row that names what it is switching to. */
    val externalLabel: String? = null,
    /**
     * Whether copying that provider's tasks into our own store is still on offer:
     * a provider is installed and permitted, and no copy has succeeded yet.
     */
    val canCopyFromExternal: Boolean = false,
    /**
     * Whether the one-shot legacy import failed and is still retryable. Almost
     * always false: the bundled provider it reads from never shipped in a release.
     */
    val legacyImportFailed: Boolean = false,
)

/** How the last copy ended — kept on screen rather than flashed past. */
sealed interface TransferOutcome {
    data class Copied(val counts: TransferCounts) : TransferOutcome
    data object NothingToCopy : TransferOutcome
    data object Failed : TransferOutcome
}

/**
 * The copy-from-external flow: a confirmation that names real numbers, then the
 * run, then a receipt that stays put.
 *
 * [preview] is null while the counts are still being read — the dialog opens
 * first and fills in, because counting means querying every list in the provider
 * and that is not instant on a big store.
 */
data class TransferUiState(
    val confirming: Boolean = false,
    val preview: TransferCounts? = null,
    val running: Boolean = false,
    val outcome: TransferOutcome? = null,
)

/**
 * Drives both the Settings screen and the app theme (MainActivity collects the
 * same instance), so a theme change applies app-wide at once.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: SettingsPrefs,
    private val resolver: ProviderResolver,
    private val environment: ProviderEnvironment,
    private val externalImport: ExternalImport,
    private val oneShotImport: OneShotImport,
    private val reminderScheduler: ReminderScheduler,
    private val taskNotifier: TaskNotifier,
    @IoDispatcher io: CoroutineDispatcher,
    repository: TasksRepository,
) : ViewModel() {

    // MainActivity collects this for the theme, above the permission gate and for
    // the whole Activity lifetime — so the list flow must survive the pre-grant
    // SecurityException and recover once permission is given, not die for good.
    val state: StateFlow<SettingsUiState> =
        combine(
            prefs.settings,
            repository.taskLists().recoveringFromProviderFailure { emptyList() },
        ) { settings, lists ->
            SettingsUiState(settings, lists)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    // Bumped to re-probe the device; installing a provider or granting its
    // permission happens outside the app, so nothing else would emit.
    private val providerProbe = MutableStateFlow(0)

    // Null until the first emission lands: the mode comes from DataStore and the
    // rest from PackageManager, off the main thread, so any seeded default would
    // name the wrong store for the first frames. flowOn, because every field here
    // costs a PackageManager lookup or a permission check.
    val storage: StateFlow<StorageUiState?> =
        combine(
            prefs.storageMode,
            providerProbe,
            externalImport.hasRun,
            oneShotImport.lastAttemptFailed,
        ) { stored, _, copied, legacyFailed ->
            val external = resolver.resolveExternal()
            StorageUiState(
                // No stored choice is the normal state; show what autoMode resolves
                // to rather than a default that may not be the store in use.
                mode = stored ?: resolver.autoMode(),
                external = external,
                externalLabel = external?.packageName?.let(environment::appLabel),
                canCopyFromExternal = !copied &&
                    external != null &&
                    resolver.hasPermission(external),
                legacyImportFailed = legacyFailed,
            )
        }.flowOn(io).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Re-read the device's provider state, after a permission request or a resume. */
    /** The list's reminder channel, created first so the system settings can open it. */
    fun listChannelId(list: TaskList): String = taskNotifier.ensureListChannel(list.id, list.name)

    fun refreshStorage() = providerProbe.update { it + 1 }

    fun setStorageMode(mode: StorageMode) = viewModelScope.launch { prefs.setStorageMode(mode) }

    // --- copying an external provider's tasks into our own store ---------------

    private val transferState = MutableStateFlow(TransferUiState())

    val transfer: StateFlow<TransferUiState> = transferState

    /** Open the confirmation and start counting what a copy would move. */
    fun startCopyFromExternal() {
        if (transferState.value.running) return
        transferState.update { it.copy(confirming = true, preview = null, outcome = null) }
        viewModelScope.launch {
            val counts = externalImport.preview()
            // The dialog may already be gone — dismissing while the count runs is
            // the normal way out of a store too big to count quickly.
            transferState.update { if (it.confirming) it.copy(preview = counts) else it }
        }
    }

    fun dismissCopyFromExternal() = transferState.update { it.copy(confirming = false) }

    /**
     * Run the copy, then re-arm reminders: the tasks that just landed carry their
     * own alarm rows, and nothing else would notice them — the scheduler is driven
     * by explicit syncs, not by a store observer.
     */
    fun confirmCopyFromExternal() {
        if (transferState.value.running) return
        transferState.update { it.copy(confirming = false, running = true, outcome = null) }
        viewModelScope.launch {
            val result = externalImport.run()
            if (result is TransferResult.Copied) runCatching { reminderScheduler.sync() }
            transferState.update {
                it.copy(
                    running = false,
                    outcome = when (result) {
                        is TransferResult.Copied -> TransferOutcome.Copied(result.counts)
                        TransferResult.NothingToCopy -> TransferOutcome.NothingToCopy
                        is TransferResult.Failed -> TransferOutcome.Failed
                    },
                )
            }
        }
    }

    /**
     * Retry the legacy import against the archived `tasks.db.imported`. Truncates
     * and replaces rather than merging, so tapping twice cannot double-import; on
     * success the flag clears and the row offering this disappears with it.
     */
    fun retryLegacyImport() {
        if (retryingLegacyImport.value) return
        retryingLegacyImport.value = true
        viewModelScope.launch {
            runCatching { oneShotImport.reimportFromArchive() }
            runCatching { reminderScheduler.sync() }
            retryingLegacyImport.value = false
        }
    }

    private val retryingLegacyImport = MutableStateFlow(false)

    val legacyImportRetrying: StateFlow<Boolean> = retryingLegacyImport

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { prefs.setDynamicColor(enabled) }
    fun setDefaultList(id: Long?) = viewModelScope.launch { prefs.setDefaultListId(id) }
    fun setDefaultReminders(leads: List<ReminderLead>) = viewModelScope.launch {
        prefs.setDefaultReminders(leads)
        resyncReminders()
    }
    fun setDefaultAllDayReminders(leads: List<ReminderLead>) = viewModelScope.launch {
        prefs.setDefaultAllDayReminders(leads)
        resyncReminders()
    }
    fun setAllDayReminderMinuteOfDay(minuteOfDay: Int) = viewModelScope.launch {
        prefs.setAllDayReminderMinuteOfDay(minuteOfDay)
        resyncReminders()
    }
    fun setRemindersEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setRemindersEnabled(enabled)
        resyncReminders()
    }
    fun setAutofocusTitle(enabled: Boolean) = viewModelScope.launch { prefs.setAutofocusTitle(enabled) }
    fun setTimeFormat(pref: TimeFormatPref) = viewModelScope.launch { prefs.setTimeFormat(pref) }
    fun setWeekStart(day: DayOfWeek?) = viewModelScope.launch { prefs.setWeekStart(day) }
    fun setSnoozeMinutes(minutes: Int) = viewModelScope.launch { prefs.setSnoozeMinutes(minutes) }
    fun setShowAddSubtaskRow(show: Boolean) = viewModelScope.launch { prefs.setShowAddSubtaskRow(show) }
    fun setBottomAddBar(enabled: Boolean) = viewModelScope.launch { prefs.setBottomAddBar(enabled) }
    fun setDefaultStartToday(enabled: Boolean) = viewModelScope.launch { prefs.setDefaultStartToday(enabled) }

    /** Toggle whether [field] shows by default on a new task's edit form. */
    fun setFormFieldDefault(field: TaskFormField, enabled: Boolean) = viewModelScope.launch {
        val current = state.value.settings.defaultEditFields
        prefs.setDefaultEditFields(if (enabled) current + field else current - field)
    }

    /** Set (or clear, via [ReminderOverride.Inherit]) a list's reminder override. */
    fun setListReminderOverride(listId: Long, override: ReminderOverride) =
        viewModelScope.launch {
            prefs.setListReminderOverride(listId, override)
            resyncReminders()
        }

    fun setListAllDayReminderOverride(listId: Long, override: ReminderOverride) =
        viewModelScope.launch {
            prefs.setListAllDayReminderOverride(listId, override)
            resyncReminders()
        }

    /** Every reminder setting moves alarms that are already armed. */
    private suspend fun resyncReminders() {
        runCatching { reminderScheduler.sync() }
    }
}

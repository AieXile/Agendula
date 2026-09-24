package de.jeanlucmakiola.agendula.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.agendula.data.tasks.recoveringFromProviderFailure
import de.jeanlucmakiola.agendula.ui.accounts.add.ADD_ACCOUNT_STEPS
import de.jeanlucmakiola.agendula.ui.common.canScheduleExactAlarms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A stop in the first-run flow. [Account] is the add-account wizard hosted
 * inline, which is why it is worth more than one segment of the progress bar.
 */
enum class OnboardingStep {
    Welcome,
    Reminders,
    ExactAlarms,
    SyncOffer,
    Account,
    FirstList,
    Backup,
    QuickAdd,
    ;

    /**
     * How many progress segments the step occupies, given the length the inline
     * wizard currently reports.
     *
     * ⚠️ Not a constant for [Account]. The wizard grows a step for a service
     * that needs an app password minted first, so its length is only known once
     * a provider has been picked — and every step after it shifts with it.
     */
    fun slots(accountSlots: Int): Int = if (this == Account) accountSlots else 1
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    /** 1-based segment this step starts at, for [OnboardingStep.Account] its first. */
    val position: Int = 1,
    val total: Int = 1,
    val canGoBack: Boolean = false,
    val bottomAddBar: Boolean = false,
)

/**
 * Drives first run: welcome, reminders, an offer to connect a CalDAV account,
 * then whatever the answer to that leaves outstanding — a list to put tasks in,
 * a copy of them off the device, and the quick-add bar.
 *
 * The flow branches, so the step list is computed rather than fixed: connecting
 * an account replaces the backup step (the server *is* the copy), and the list
 * step only appears for a user who would otherwise finish with nowhere to put a
 * task — which a synced account usually settles on its own.
 *
 * Going back is offered only where nothing has been written yet. Once a list
 * exists or an account has been added, the way on is forward.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SettingsPrefs,
    private val repository: TasksRepository,
) : ViewModel() {

    /** Null until DataStore's first emission, so the flow neither flashes nor is skipped. */
    val done: StateFlow<Boolean?> = prefs.onboardingDone
        .map { it as Boolean? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    private val hasLists = repository.taskLists()
        .recoveringFromProviderFailure { emptyList() }
        .map { it.isNotEmpty() }

    private val step = MutableStateFlow(OnboardingStep.Welcome)

    /**
     * How long the inline add-account wizard says it is.
     *
     * Seeded with its shortest form and corrected by the flow itself once a
     * provider is chosen, so the bar is honest from the first frame and stays
     * honest when the choice makes the flow longer.
     */
    private val accountSlots = MutableStateFlow(ADD_ACCOUNT_STEPS)

    fun onAccountStepsChanged(count: Int) {
        accountSlots.value = count
    }

    /** Answered at [OnboardingStep.SyncOffer]; null while it is still the question. */
    private val connectsAccount = MutableStateFlow<Boolean?>(null)

    /**
     * Snapshotted rather than observed: the list step decides whether it belongs
     * in the flow *before* it runs, and must not vanish from under the user the
     * moment their own list makes the answer false. Seeded from the store so the
     * progress bar is honest from the first frame rather than resizing later.
     */
    private val needsList = MutableStateFlow(true)

    init {
        viewModelScope.launch { needsList.value = !hasLists.first() }
    }

    /**
     * Whether the exact-alarm step is in the flow: reminders on and the grant
     * missing. Counted up front since reminders default on, dropped by "not now".
     */
    private val needsExactAlarms = MutableStateFlow(!context.canScheduleExactAlarms())

    val state: StateFlow<OnboardingUiState> =
        combine(
            step,
            connectsAccount,
            combine(needsList, needsExactAlarms, ::Pair),
            prefs.settings,
            accountSlots,
        ) { step, sync, (needsList, needsExact), settings, slots ->
            val plan = plan(sync ?: false, needsList, needsExact)
            OnboardingUiState(
                step = step,
                position = plan.takeWhile { it != step }.sumOf { it.slots(slots) } + 1,
                total = plan.sumOf { it.slots(slots) },
                canGoBack = canGoBack(step, sync),
                bottomAddBar = settings.bottomAddBar,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), OnboardingUiState())

    fun onWelcomeDone() = advanceTo(OnboardingStep.Reminders)

    /** Reminders default on; "not now" turns the in-app toggle off. */
    fun onRemindersAnswered(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setRemindersEnabled(enabled)
            needsExactAlarms.value = enabled && !context.canScheduleExactAlarms()
            advanceTo(if (needsExactAlarms.value) OnboardingStep.ExactAlarms else OnboardingStep.SyncOffer)
        }
    }

    fun onExactAlarmsDone() = advanceTo(OnboardingStep.SyncOffer)

    fun onSyncAnswered(connect: Boolean) {
        connectsAccount.value = connect
        if (connect) {
            step.value = OnboardingStep.Account
        } else {
            advanceTo(OnboardingStep.FirstList)
        }
    }

    /** The wizard finished, or was abandoned from its own first step. */
    fun onAccountFinished(added: Boolean) {
        if (!added) {
            connectsAccount.value = null
            step.value = OnboardingStep.SyncOffer
            return
        }
        advanceTo(OnboardingStep.FirstList)
    }

    fun createFirstList(name: String, color: Int) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { repository.createLocalList(name.trim(), color) }
            afterFirstList()
        }
    }

    fun onBackupDone() = advanceTo(OnboardingStep.QuickAdd)

    fun setBottomAddBar(enabled: Boolean) {
        viewModelScope.launch { prefs.setBottomAddBar(enabled) }
    }

    fun finish() {
        viewModelScope.launch { prefs.setOnboardingDone() }
    }

    fun back() {
        step.value = when (step.value) {
            OnboardingStep.Reminders -> OnboardingStep.Welcome
            OnboardingStep.ExactAlarms -> OnboardingStep.Reminders
            // Once granted there is nothing left to ask, so back skips the step.
            OnboardingStep.SyncOffer ->
                if (needsExactAlarms.value && !context.canScheduleExactAlarms()) OnboardingStep.ExactAlarms
                else OnboardingStep.Reminders
            OnboardingStep.FirstList -> OnboardingStep.SyncOffer
            else -> return
        }
    }

    /**
     * Back is offered only where nothing has been written. The list step
     * qualifies on the way through the local branch, but not when an account has
     * just been added behind it — there is nothing back there to change.
     */
    private fun canGoBack(step: OnboardingStep, sync: Boolean?): Boolean = when (step) {
        OnboardingStep.Reminders, OnboardingStep.ExactAlarms, OnboardingStep.SyncOffer -> true
        OnboardingStep.FirstList -> sync != true
        else -> false
    }

    /**
     * Moves to [target], or past it when the flow no longer needs it. Only the
     * list step is conditional, and only it re-reads the store: a synced account
     * has usually brought lists of its own by now, and a restored backup may have
     * done the same.
     */
    private fun advanceTo(target: OnboardingStep) {
        if (target != OnboardingStep.FirstList) {
            step.value = target
            return
        }
        viewModelScope.launch {
            val already = hasLists.first()
            needsList.value = !already
            step.value = if (already) stepAfterFirstList() else OnboardingStep.FirstList
        }
    }

    private fun afterFirstList() {
        step.value = stepAfterFirstList()
    }

    private fun stepAfterFirstList(): OnboardingStep =
        if (connectsAccount.value == true) OnboardingStep.QuickAdd else OnboardingStep.Backup

    private fun plan(sync: Boolean, needsList: Boolean, needsExact: Boolean): List<OnboardingStep> = buildList {
        add(OnboardingStep.Welcome)
        add(OnboardingStep.Reminders)
        if (needsExact) add(OnboardingStep.ExactAlarms)
        add(OnboardingStep.SyncOffer)
        if (sync) add(OnboardingStep.Account)
        if (needsList) add(OnboardingStep.FirstList)
        if (!sync) add(OnboardingStep.Backup)
        add(OnboardingStep.QuickAdd)
    }
}

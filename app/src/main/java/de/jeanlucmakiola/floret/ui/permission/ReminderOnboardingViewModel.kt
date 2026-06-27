package de.jeanlucmakiola.floret.ui.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.floret.data.prefs.SettingsPrefs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Gates the one-time reminder onboarding step shown after the tasks-provider
 * grant. [onboardingDone] is null until DataStore's first emission so the step
 * neither flashes for users who completed it nor gets skipped.
 */
@HiltViewModel
class ReminderOnboardingViewModel @Inject constructor(
    private val prefs: SettingsPrefs,
) : ViewModel() {

    val onboardingDone: StateFlow<Boolean?> = prefs.reminderOnboardingDone
        .map { done -> done as Boolean? }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = null,
        )

    /** Close the step, recording whether due reminders stay on. */
    fun finish(remindersEnabled: Boolean) {
        viewModelScope.launch {
            prefs.setRemindersEnabled(remindersEnabled)
            prefs.setReminderOnboardingDone()
        }
    }
}

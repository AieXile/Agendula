package de.jeanlucmakiola.agendula.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.agendula.data.prefs.Settings
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.agendula.data.prefs.ThemeMode
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.agendula.domain.TaskFormField
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.floret.reminders.ReminderOverride
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: Settings = Settings(),
    val lists: List<TaskList> = emptyList(),
)

/**
 * Drives both the Settings screen and the app theme (MainActivity collects the
 * same instance), so a theme change applies app-wide at once.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: SettingsPrefs,
    repository: TasksRepository,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> =
        combine(prefs.settings, repository.taskLists().catch { emit(emptyList()) }) { settings, lists ->
            SettingsUiState(settings, lists)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { prefs.setDynamicColor(enabled) }
    fun setDefaultList(id: Long?) = viewModelScope.launch { prefs.setDefaultListId(id) }
    fun setReminderLeadMinutes(minutes: Int) = viewModelScope.launch { prefs.setReminderLeadMinutes(minutes) }
    fun setRemindersEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setRemindersEnabled(enabled) }
    fun setShowAddSubtaskRow(show: Boolean) = viewModelScope.launch { prefs.setShowAddSubtaskRow(show) }
    fun setBottomAddBar(enabled: Boolean) = viewModelScope.launch { prefs.setBottomAddBar(enabled) }

    /** Toggle whether [field] shows by default on a new task's edit form. */
    fun setFormFieldDefault(field: TaskFormField, enabled: Boolean) = viewModelScope.launch {
        val current = state.value.settings.defaultEditFields
        prefs.setDefaultEditFields(if (enabled) current + field else current - field)
    }

    /** Set (or clear, via [ReminderOverride.Inherit]) a list's reminder override. */
    fun setListReminderOverride(listId: Long, override: ReminderOverride) =
        viewModelScope.launch { prefs.setListReminderOverride(listId, override) }
}

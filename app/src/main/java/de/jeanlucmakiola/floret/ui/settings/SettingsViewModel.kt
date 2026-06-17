package de.jeanlucmakiola.floret.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.floret.data.prefs.Settings
import de.jeanlucmakiola.floret.data.prefs.SettingsPrefs
import de.jeanlucmakiola.floret.data.prefs.ThemeMode
import de.jeanlucmakiola.floret.data.tasks.TasksRepository
import de.jeanlucmakiola.floret.domain.TaskList
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
}

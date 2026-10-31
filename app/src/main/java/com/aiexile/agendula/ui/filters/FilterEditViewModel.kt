package com.aiexile.agendula.ui.filters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.aiexile.agendula.data.prefs.SettingsPrefs
import com.aiexile.agendula.data.tasks.TasksRepository
import com.aiexile.agendula.domain.SavedFilter
import com.aiexile.agendula.domain.SmartList
import com.aiexile.agendula.domain.TaskFilter
import com.aiexile.agendula.domain.TaskList
import com.aiexile.agendula.domain.tagsInUse
import com.aiexile.agendula.widget.TaskWidgetUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Holds the filter being created or edited until it's saved. Call [bind] first. */
@HiltViewModel
class FilterEditViewModel @Inject constructor(
    private val settingsPrefs: SettingsPrefs,
    private val widgetUpdater: TaskWidgetUpdater,
    private val repository: TasksRepository,
) : ViewModel() {

    private val _draft = MutableStateFlow<SavedFilter?>(null)

    /** `null` until [bind] has loaded the filter. An id of 0 is a new one. */
    val draft: StateFlow<SavedFilter?> = _draft.asStateFlow()

    val lists: StateFlow<List<TaskList>> = repository.taskLists()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every tag on an open task. */
    val tags: StateFlow<List<String>> = repository.tasks(TaskFilter.Smart(SmartList.ALL))
        .map { tagsInUse(it) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var bound = false
    private var saving = false

    fun bind(filterId: Long?) {
        if (bound) return
        bound = true
        if (filterId == null) {
            _draft.value = SavedFilter(id = 0, name = "")
            return
        }
        viewModelScope.launch {
            val saved = settingsPrefs.savedFilter(filterId).first()
            // Show it as it matches: lists deleted since drop out, as they do in the repository.
            val existing = runCatching { repository.taskLists().first().mapTo(HashSet()) { it.id } }.getOrNull()
            _draft.value = saved?.let { if (existing == null) it else it.withListsIn(existing) }
                ?: SavedFilter(id = 0, name = "")
        }
    }

    fun update(transform: (SavedFilter) -> SavedFilter) {
        _draft.value = _draft.value?.let(transform)
    }

    fun save(onSaved: (Long) -> Unit) {
        val filter = _draft.value ?: return
        if (filter.name.isBlank() || saving) return
        saving = true
        viewModelScope.launch {
            val id = settingsPrefs.saveFilter(filter.copy(name = filter.name.trim()))
            // A widget showing this filter doesn't watch the prefs.
            widgetUpdater.requestRefresh()
            onSaved(id)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = _draft.value?.id?.takeIf { it > 0 } ?: return
        viewModelScope.launch {
            settingsPrefs.deleteFilter(id)
            widgetUpdater.requestRefresh()
            onDeleted()
        }
    }
}

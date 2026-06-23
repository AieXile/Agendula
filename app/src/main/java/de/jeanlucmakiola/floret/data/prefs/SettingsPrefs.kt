package de.jeanlucmakiola.floret.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import de.jeanlucmakiola.floret.domain.TaskFormField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** The list a new task defaults to; `null` = first available. */
    val defaultListId: Long? = null,
    /** Default minutes before due to remind; 0 = at due time. */
    val reminderLeadMinutes: Int = 0,
    /** Optional edit-form fields shown by default; the rest sit behind "More fields". */
    val defaultEditFields: Set<TaskFormField> = emptySet(),
)

/** App preferences, backed by DataStore. Mirrors Calendula's prefs shape. */
@Singleton
class SettingsPrefs @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<Settings> = dataStore.data.map { p ->
        Settings(
            themeMode = p[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = p[DYNAMIC_COLOR] ?: true,
            defaultListId = p[DEFAULT_LIST_ID]?.takeIf { it > 0 },
            reminderLeadMinutes = p[REMINDER_LEAD] ?: 0,
            defaultEditFields = p[DEFAULT_EDIT_FIELDS].orEmpty()
                .mapNotNull { name -> runCatching { TaskFormField.valueOf(name) }.getOrNull() }
                .toSet(),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = dataStore.edit { it[THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    suspend fun setDefaultListId(id: Long?) = dataStore.edit {
        if (id == null) it.remove(DEFAULT_LIST_ID) else it[DEFAULT_LIST_ID] = id
    }

    suspend fun setReminderLeadMinutes(minutes: Int) = dataStore.edit { it[REMINDER_LEAD] = minutes }

    suspend fun setDefaultEditFields(fields: Set<TaskFormField>) = dataStore.edit {
        it[DEFAULT_EDIT_FIELDS] = fields.mapTo(mutableSetOf()) { field -> field.name }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DEFAULT_LIST_ID = longPreferencesKey("default_list_id")
        val REMINDER_LEAD = intPreferencesKey("reminder_lead_minutes")
        val DEFAULT_EDIT_FIELDS = stringSetPreferencesKey("default_edit_fields")
    }
}

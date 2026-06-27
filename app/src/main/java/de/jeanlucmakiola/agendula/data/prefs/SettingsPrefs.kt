package de.jeanlucmakiola.agendula.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import de.jeanlucmakiola.agendula.domain.TaskFormField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** A list's override of the default reminder lead time (mirrors Calendula's per-calendar override). */
sealed interface ListReminderOverride {
    /** No override — the list uses the global default. */
    data object Inherit : ListReminderOverride
    /** Explicit "no reminder" for this list, regardless of the global default. */
    data object None : ListReminderOverride
    /** A specific lead time in minutes before due. */
    data class Minutes(val minutes: Int) : ListReminderOverride
}

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** The list a new task defaults to; `null` = first available. */
    val defaultListId: Long? = null,
    /** Default minutes before due to remind; 0 = at due time. */
    val reminderLeadMinutes: Int = 0,
    /** Master switch for due reminders; off clears every scheduled alarm. */
    val remindersEnabled: Boolean = true,
    /** Whether the inline "add a subtask" row shows on expanded task-list groups. */
    val showAddSubtaskRow: Boolean = true,
    /**
     * Per-list overrides of [reminderLeadMinutes]: a list present in the map
     * overrides the global default (a null value = no reminder); absent = inherit.
     */
    val perListReminderOverride: Map<Long, Int?> = emptyMap(),
    /** Optional edit-form fields shown by default; the rest sit behind "More fields". */
    val defaultEditFields: Set<TaskFormField> = emptySet(),
) {
    /** The lead time for a task in [listId]: its override if set, else the global default. */
    fun reminderLeadFor(listId: Long): Int? =
        if (perListReminderOverride.containsKey(listId)) {
            perListReminderOverride[listId] // null = explicitly no reminder
        } else {
            reminderLeadMinutes
        }
}

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
            remindersEnabled = p[REMINDERS_ENABLED] ?: true,
            showAddSubtaskRow = p[SHOW_ADD_SUBTASK_ROW] ?: true,
            perListReminderOverride = parseReminderOverrides(p[LIST_REMINDER_OVERRIDE]),
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

    /** One-time reminder onboarding gate; false until the step has been shown. */
    val reminderOnboardingDone: Flow<Boolean> = dataStore.data.map { it[REMINDER_ONBOARDING_DONE] ?: false }

    suspend fun setReminderOnboardingDone() = dataStore.edit { it[REMINDER_ONBOARDING_DONE] = true }

    suspend fun setRemindersEnabled(enabled: Boolean) = dataStore.edit { it[REMINDERS_ENABLED] = enabled }

    suspend fun setShowAddSubtaskRow(show: Boolean) = dataStore.edit { it[SHOW_ADD_SUBTASK_ROW] = show }

    /** Set (or clear, via [ListReminderOverride.Inherit]) a list's reminder override. */
    suspend fun setListReminderOverride(listId: Long, override: ListReminderOverride) = dataStore.edit { p ->
        val current = parseReminderOverrides(p[LIST_REMINDER_OVERRIDE]).toMutableMap()
        when (override) {
            ListReminderOverride.Inherit -> current.remove(listId)
            ListReminderOverride.None -> current[listId] = null
            is ListReminderOverride.Minutes -> current[listId] = override.minutes
        }
        p[LIST_REMINDER_OVERRIDE] = serializeReminderOverrides(current)
    }

    suspend fun setDefaultEditFields(fields: Set<TaskFormField>) = dataStore.edit {
        it[DEFAULT_EDIT_FIELDS] = fields.mapTo(mutableSetOf()) { field -> field.name }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DEFAULT_LIST_ID = longPreferencesKey("default_list_id")
        val REMINDER_LEAD = intPreferencesKey("reminder_lead_minutes")
        val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        val SHOW_ADD_SUBTASK_ROW = booleanPreferencesKey("show_add_subtask_row")
        val REMINDER_ONBOARDING_DONE = booleanPreferencesKey("reminder_onboarding_done")
        val LIST_REMINDER_OVERRIDE = stringPreferencesKey("list_reminder_override")
        val DEFAULT_EDIT_FIELDS = stringSetPreferencesKey("default_edit_fields")
    }
}

private const val OVERRIDE_ENTRY_SEP = ";"
private const val OVERRIDE_KV_SEP = ":"
private const val OVERRIDE_NONE = "none"

/** Decode "id:minutes;id:none;…" into a map (value null = explicit no-reminder). */
private fun parseReminderOverrides(stored: String?): Map<Long, Int?> {
    if (stored.isNullOrBlank()) return emptyMap()
    return stored.split(OVERRIDE_ENTRY_SEP).mapNotNull { entry ->
        val parts = entry.split(OVERRIDE_KV_SEP).takeIf { it.size == 2 } ?: return@mapNotNull null
        val id = parts[0].toLongOrNull() ?: return@mapNotNull null
        val value = if (parts[1] == OVERRIDE_NONE) null else parts[1].toIntOrNull() ?: return@mapNotNull null
        id to value
    }.toMap()
}

private fun serializeReminderOverrides(map: Map<Long, Int?>): String =
    map.entries.joinToString(OVERRIDE_ENTRY_SEP) { (id, minutes) -> "$id$OVERRIDE_KV_SEP${minutes ?: OVERRIDE_NONE}" }

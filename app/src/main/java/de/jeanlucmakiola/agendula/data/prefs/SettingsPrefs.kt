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
import de.jeanlucmakiola.floret.reminders.ReminderOverride
import de.jeanlucmakiola.floret.reminders.ReminderOverrideCodec
import de.jeanlucmakiola.floret.reminders.applyReminderOverride
import de.jeanlucmakiola.floret.reminders.reminderLeadsFor
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
    /** Master switch for due reminders; off clears every scheduled alarm. */
    val remindersEnabled: Boolean = true,
    /** Whether the inline "add a subtask" row shows on expanded task-list groups. */
    val showAddSubtaskRow: Boolean = true,
    /**
     * Add affordance for a list: `false` = the floating "New task" button (opens
     * the editor); `true` = a quick-add bar pinned to the bottom of a real list.
     * Smart lists always use the button (they have no single list to add into).
     */
    val bottomAddBar: Boolean = false,
    /**
     * Per-list overrides of [reminderLeadMinutes]: a list present in the map
     * overrides the global default (an empty list = no reminder); absent =
     * inherit. Agendula offers a single reminder, so each override is a
     * one-element (or empty) list.
     */
    val perListReminderOverride: Map<Long, List<Int>> = emptyMap(),
    /** Optional edit-form fields shown by default; the rest sit behind "More fields". */
    val defaultEditFields: Set<TaskFormField> = emptySet(),
) {
    /** The lead time for a task in [listId]: its override if set, else the global default. */
    fun reminderLeadFor(listId: Long): Int? =
        perListReminderOverride.reminderLeadsFor(listId, listOf(reminderLeadMinutes)).firstOrNull()
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
            bottomAddBar = p[BOTTOM_ADD_BAR] ?: false,
            perListReminderOverride = reminderCodec.parse(p[LIST_REMINDER_OVERRIDE]),
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

    suspend fun setBottomAddBar(enabled: Boolean) = dataStore.edit { it[BOTTOM_ADD_BAR] = enabled }

    /** Set (or clear, via [ReminderOverride.Inherit]) a list's reminder override. */
    suspend fun setListReminderOverride(listId: Long, override: ReminderOverride) = dataStore.edit { p ->
        val current = reminderCodec.parse(p[LIST_REMINDER_OVERRIDE]).toMutableMap()
        current.applyReminderOverride(listId, override)
        p[LIST_REMINDER_OVERRIDE] = reminderCodec.serialize(current)
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
        val BOTTOM_ADD_BAR = booleanPreferencesKey("bottom_add_bar")
        val REMINDER_ONBOARDING_DONE = booleanPreferencesKey("reminder_onboarding_done")
        val LIST_REMINDER_OVERRIDE = stringPreferencesKey("list_reminder_override")
        val DEFAULT_EDIT_FIELDS = stringSetPreferencesKey("default_edit_fields")
    }
}

/**
 * Agendula's stored dialect for the per-list override map: `id:minutes` entries
 * joined by `;` (the family default). Fixed at release — don't change without a
 * data migration.
 */
private val reminderCodec = ReminderOverrideCodec.DEFAULT

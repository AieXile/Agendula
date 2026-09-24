package de.jeanlucmakiola.agendula.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import de.jeanlucmakiola.agendula.data.tasks.ProviderResolver
import de.jeanlucmakiola.agendula.data.tasks.StorageMode
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskFormField
import de.jeanlucmakiola.agendula.domain.TaskSortOrder
import de.jeanlucmakiola.floret.reminders.ReminderOverride
import de.jeanlucmakiola.floret.reminders.ReminderOverrideCodec
import de.jeanlucmakiola.floret.reminders.applyReminderOverride
import de.jeanlucmakiola.floret.reminders.normalizeReminders
import de.jeanlucmakiola.floret.reminders.reminderLeadsFor
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Clock convention: AUTO follows the device's 24-hour switch. */
enum class TimeFormatPref { AUTO, TWELVE_HOUR, TWENTY_FOUR_HOUR }

fun TimeFormatPref.is24Hour(systemIs24Hour: Boolean): Boolean = when (this) {
    TimeFormatPref.AUTO -> systemIs24Hour
    TimeFormatPref.TWELVE_HOUR -> false
    TimeFormatPref.TWENTY_FOUR_HOUR -> true
}

const val DEFAULT_SNOOZE_MINUTES = 10

/** The snooze lengths Settings offers. */
val SNOOZE_PRESETS = listOf(5, 10, 15, 30, 60)

/** Minutes between background syncs; 0 = only when asked. */
const val DEFAULT_SYNC_INTERVAL_MINUTES = 240

/** The sync intervals Settings offers; 15 minutes is WorkManager's floor. */
val SYNC_INTERVAL_PRESETS = listOf(15, 30, 60, 120, 240, 720, 1_440, 0)

/** 09:00. */
const val DEFAULT_ALL_DAY_REMINDER_MINUTE = 9 * 60

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** The list a new task defaults to; `null` = first available. */
    val defaultListId: Long? = null,
    /** Default reminders, as minutes before due (0 = at due time); empty = none. */
    val defaultReminderMinutes: List<Int> = listOf(0),
    /** Default reminders for all-day tasks, as days-scale minutes before [allDayReminderMinuteOfDay]. */
    val defaultAllDayReminderMinutes: List<Int> = listOf(0),
    /** Local time (minutes from midnight) an all-day task's reminder counts back from. */
    val allDayReminderMinuteOfDay: Int = DEFAULT_ALL_DAY_REMINDER_MINUTE,
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
     * Per-list overrides of [defaultReminderMinutes]: a list present in the map
     * overrides the global default (an empty list = no reminder); absent =
     * inherit.
     */
    val perListReminderOverride: Map<Long, List<Int>> = emptyMap(),
    /** Per-list overrides of [defaultAllDayReminderMinutes], same shape as [perListReminderOverride]. */
    val perListAllDayReminderOverride: Map<Long, List<Int>> = emptyMap(),
    /** Optional edit-form fields shown by default; the rest sit behind "More fields". */
    val defaultEditFields: Set<TaskFormField> = emptySet(),
    val sortOrder: TaskSortOrder = TaskSortOrder.DUE,
    /** How long a reminder's "Snooze" action puts it off. */
    val snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    val timeFormat: TimeFormatPref = TimeFormatPref.AUTO,
    /** The first day of the week; `null` follows the locale. */
    val weekStart: DayOfWeek? = null,
    /** Put the cursor in the title (and raise the keyboard) when a new task opens. */
    val autofocusTitle: Boolean = true,
    /** Minutes between background syncs of every account; 0 = manual only. */
    val syncIntervalMinutes: Int = DEFAULT_SYNC_INTERVAL_MINUTES,
    /** Take server changes by push when a UnifiedPush distributor is installed. */
    val pushEnabled: Boolean = true,
    /** Lists of the current store whose tasks the smart lists (and their counts) leave out. */
    val hiddenFromSmartLists: Set<Long> = emptySet(),
) {
    /** [tasks] as [filter] shows them: a smart list drops the lists kept out of it. */
    fun visibleIn(filter: TaskFilter, tasks: List<Task>): List<Task> =
        if (filter is TaskFilter.Smart && hiddenFromSmartLists.isNotEmpty()) {
            tasks.filter { it.listId !in hiddenFromSmartLists }
        } else {
            tasks
        }

    /** The lead times for a task in [listId]: its override if set, else the global default. */
    fun reminderLeadsFor(listId: Long): List<Int> =
        perListReminderOverride.reminderLeadsFor(listId, defaultReminderMinutes)

    /** The lead times for an all-day task in [listId]. */
    fun allDayReminderLeadsFor(listId: Long): List<Int> =
        perListAllDayReminderOverride.reminderLeadsFor(listId, defaultAllDayReminderMinutes)
}

/** App preferences, backed by DataStore. Mirrors Calendula's prefs shape. */
@Singleton
class SettingsPrefs @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val resolver: ProviderResolver,
) {
    val settings: Flow<Settings> = dataStore.data.map { p ->
        Settings(
            themeMode = p[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = p[DYNAMIC_COLOR] ?: true,
            defaultListId = p[DEFAULT_LIST_ID]?.takeIf { it > 0 },
            // The single lead of earlier versions carries over until a list is saved.
            defaultReminderMinutes = p[DEFAULT_REMINDERS]?.let(::parseMinutes)
                ?: listOf(p[REMINDER_LEAD] ?: 0),
            defaultAllDayReminderMinutes = p[DEFAULT_ALL_DAY_REMINDERS]?.let(::parseMinutes) ?: listOf(0),
            allDayReminderMinuteOfDay = p[ALL_DAY_REMINDER_MINUTE]?.takeIf { it in 0 until 24 * 60 }
                ?: DEFAULT_ALL_DAY_REMINDER_MINUTE,
            remindersEnabled = p[REMINDERS_ENABLED] ?: true,
            showAddSubtaskRow = p[SHOW_ADD_SUBTASK_ROW] ?: true,
            bottomAddBar = p[BOTTOM_ADD_BAR] ?: false,
            perListReminderOverride = reminderCodec.parse(p[LIST_REMINDER_OVERRIDE]),
            perListAllDayReminderOverride = reminderCodec.parse(p[LIST_ALL_DAY_REMINDER_OVERRIDE]),
            defaultEditFields = p[DEFAULT_EDIT_FIELDS].orEmpty()
                .mapNotNull { name -> runCatching { TaskFormField.valueOf(name) }.getOrNull() }
                .toSet(),
            sortOrder = p[SORT_ORDER]?.let { runCatching { TaskSortOrder.valueOf(it) }.getOrNull() }
                ?: TaskSortOrder.DUE,
            snoozeMinutes = p[SNOOZE_MINUTES]?.takeIf { it > 0 } ?: DEFAULT_SNOOZE_MINUTES,
            timeFormat = p[TIME_FORMAT]?.let { runCatching { TimeFormatPref.valueOf(it) }.getOrNull() }
                ?: TimeFormatPref.AUTO,
            autofocusTitle = p[AUTOFOCUS_TITLE] ?: true,
            syncIntervalMinutes = p[SYNC_INTERVAL]?.takeIf { it == 0 || it >= 15 } ?: DEFAULT_SYNC_INTERVAL_MINUTES,
            weekStart = p[WEEK_START]?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() },
            pushEnabled = p[PUSH_ENABLED] ?: true,
            hiddenFromSmartLists = modeOf(p).name.let { mode ->
                p[SMART_LIST_HIDDEN].orEmpty()
                    .mapNotNull { entry -> entry.substringAfter("$mode:", "").toLongOrNull() }
                    .toSet()
            },
        )
    }

    suspend fun setSortOrder(order: TaskSortOrder) = dataStore.edit { it[SORT_ORDER] = order.name }

    suspend fun setSnoozeMinutes(minutes: Int) = dataStore.edit { it[SNOOZE_MINUTES] = minutes.coerceAtLeast(1) }

    suspend fun setSyncIntervalMinutes(minutes: Int) = dataStore.edit { it[SYNC_INTERVAL] = minutes }

    suspend fun setPushEnabled(enabled: Boolean) = dataStore.edit { it[PUSH_ENABLED] = enabled }

    suspend fun setAutofocusTitle(enabled: Boolean) = dataStore.edit { it[AUTOFOCUS_TITLE] = enabled }

    suspend fun setTimeFormat(pref: TimeFormatPref) = dataStore.edit { it[TIME_FORMAT] = pref.name }

    suspend fun setWeekStart(day: DayOfWeek?) = dataStore.edit {
        if (day == null) it.remove(WEEK_START) else it[WEEK_START] = day.name
    }

    suspend fun setThemeMode(mode: ThemeMode) = dataStore.edit { it[THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    suspend fun setDefaultListId(id: Long?) = dataStore.edit {
        if (id == null) it.remove(DEFAULT_LIST_ID) else it[DEFAULT_LIST_ID] = id
    }

    suspend fun setDefaultReminderMinutes(minutes: List<Int>) = dataStore.edit {
        it[DEFAULT_REMINDERS] = minutes.normalizeReminders().joinToString(",")
    }

    suspend fun setDefaultAllDayReminderMinutes(minutes: List<Int>) = dataStore.edit {
        it[DEFAULT_ALL_DAY_REMINDERS] = minutes.normalizeReminders().joinToString(",")
    }

    suspend fun setAllDayReminderMinuteOfDay(minuteOfDay: Int) =
        dataStore.edit { it[ALL_DAY_REMINDER_MINUTE] = minuteOfDay.coerceIn(0, 24 * 60 - 1) }

    /**
     * Which task store backs the app, or `null` while the user has not chosen —
     * which is the normal state, since most people never open Settings.
     *
     * Kept out of [Settings] on purpose. Everything in there is a rendering
     * preference collected by the UI; this one selects an authority in the data
     * layer, is read on paths that must not wait for a whole settings object, and
     * `null` genuinely means "undecided" rather than "default" — the difference
     * matters, because undecided is what lets `ProviderResolver.autoMode` keep an
     * upgrading Posture A user pointed at the provider that holds their data.
     */
    val storageMode: Flow<StorageMode?> = dataStore.data.map { p -> storedMode(p[STORAGE_MODE]) }

    private fun storedMode(stored: String?): StorageMode? = when (stored) {
        null -> null
        // 0.3.x's value for the bundled dmfs provider. That store is gone and
        // its data was imported into OWN, so read it as OWN rather than
        // letting it fall through to autoMode — someone who chose local
        // storage explicitly would otherwise be sent to an external provider.
        "LOCAL" -> StorageMode.OWN
        else -> runCatching { StorageMode.valueOf(stored) }.getOrNull()
    }

    /** The store list ids belong to; each store numbers its lists on its own. */
    private fun modeOf(p: Preferences): StorageMode = storedMode(p[STORAGE_MODE]) ?: resolver.autoMode()

    suspend fun setStorageMode(mode: StorageMode) = dataStore.edit { it[STORAGE_MODE] = mode.name }

    /**
     * One-time first-run gate; false until the flow has been walked through.
     *
     * The key still says `reminder_onboarding_done` — it gated a single reminder
     * step before the flow grew around it, and renaming it would drag every
     * existing install back through onboarding.
     */
    val onboardingDone: Flow<Boolean> = dataStore.data.map { it[ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingDone() = dataStore.edit { it[ONBOARDING_DONE] = true }

    suspend fun setRemindersEnabled(enabled: Boolean) = dataStore.edit { it[REMINDERS_ENABLED] = enabled }

    suspend fun setShowAddSubtaskRow(show: Boolean) = dataStore.edit { it[SHOW_ADD_SUBTASK_ROW] = show }

    suspend fun setBottomAddBar(enabled: Boolean) = dataStore.edit { it[BOTTOM_ADD_BAR] = enabled }

    /** Set (or clear, via [ReminderOverride.Inherit]) a list's reminder override. */
    suspend fun setListReminderOverride(listId: Long, override: ReminderOverride) = dataStore.edit { p ->
        val current = reminderCodec.parse(p[LIST_REMINDER_OVERRIDE]).toMutableMap()
        current.applyReminderOverride(listId, override)
        p[LIST_REMINDER_OVERRIDE] = reminderCodec.serialize(current)
    }

    /** Set (or clear) a list's all-day reminder override. */
    suspend fun setListAllDayReminderOverride(listId: Long, override: ReminderOverride) = dataStore.edit { p ->
        val current = reminderCodec.parse(p[LIST_ALL_DAY_REMINDER_OVERRIDE]).toMutableMap()
        current.applyReminderOverride(listId, override)
        p[LIST_ALL_DAY_REMINDER_OVERRIDE] = reminderCodec.serialize(current)
    }

    suspend fun setHiddenFromSmartLists(listId: Long, hidden: Boolean) = dataStore.edit { p ->
        val entry = "${modeOf(p).name}:$listId"
        val current = p[SMART_LIST_HIDDEN].orEmpty()
        p[SMART_LIST_HIDDEN] = if (hidden) current + entry else current - entry
    }

    suspend fun setDefaultEditFields(fields: Set<TaskFormField>) = dataStore.edit {
        it[DEFAULT_EDIT_FIELDS] = fields.mapTo(mutableSetOf()) { field -> field.name }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DEFAULT_LIST_ID = longPreferencesKey("default_list_id")
        val REMINDER_LEAD = intPreferencesKey("reminder_lead_minutes")
        val DEFAULT_REMINDERS = stringPreferencesKey("default_reminder_minutes")
        val DEFAULT_ALL_DAY_REMINDERS = stringPreferencesKey("default_all_day_reminder_minutes")
        val LIST_ALL_DAY_REMINDER_OVERRIDE = stringPreferencesKey("list_all_day_reminder_override")
        val ALL_DAY_REMINDER_MINUTE = intPreferencesKey("all_day_reminder_minute")
        val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        val SHOW_ADD_SUBTASK_ROW = booleanPreferencesKey("show_add_subtask_row")
        val BOTTOM_ADD_BAR = booleanPreferencesKey("bottom_add_bar")
        val ONBOARDING_DONE = booleanPreferencesKey("reminder_onboarding_done")
        val STORAGE_MODE = stringPreferencesKey("storage_mode")
        val LIST_REMINDER_OVERRIDE = stringPreferencesKey("list_reminder_override")
        val DEFAULT_EDIT_FIELDS = stringSetPreferencesKey("default_edit_fields")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")
        val TIME_FORMAT = stringPreferencesKey("time_format")
        val WEEK_START = stringPreferencesKey("week_start")
        val AUTOFOCUS_TITLE = booleanPreferencesKey("autofocus_title")
        val SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes")
        val PUSH_ENABLED = booleanPreferencesKey("push_enabled")
        val SMART_LIST_HIDDEN = stringSetPreferencesKey("smart_list_hidden")
    }
}

/**
 * Agendula's stored dialect for the per-list override map: `id:minutes` entries
 * joined by `;` (the family default). Fixed at release — don't change without a
 * data migration.
 */
private val reminderCodec = ReminderOverrideCodec.DEFAULT

/** `5,30` → [5, 30]; an empty string is an explicit "no reminder". */
private fun parseMinutes(stored: String): List<Int> =
    stored.split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { m -> m >= 0 } }.normalizeReminders()

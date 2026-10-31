package com.aiexile.agendula.widget

import android.content.Context
import android.text.format.DateFormat
import com.aiexile.agendula.data.prefs.ThemeMode
import com.aiexile.agendula.data.prefs.is24Hour
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aiexile.agendula.domain.SmartList
import com.aiexile.agendula.domain.Task
import com.aiexile.agendula.domain.TaskFilter
import com.aiexile.agendula.domain.calendarDate
import com.aiexile.agendula.domain.comparator
import de.jeanlucmakiola.floret.glance.WidgetBackground
import de.jeanlucmakiola.floret.glance.WidgetThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Instant

/** Per-instance Glance state key holding the widget's [TaskFilter] ([widgetStorageValue]). */
internal val WIDGET_FILTER_KEY = stringPreferencesKey("task_filter")

/** Per-instance Glance state key: group a multi-list widget's rows under list headers. */
internal val WIDGET_GROUP_BY_LIST_KEY = booleanPreferencesKey("group_by_list")

/** Per-instance Glance state key holding the widget's [WidgetBackground] ([name]); unset = [WidgetBackground.SOLID]. */
internal val WIDGET_BACKGROUND_KEY = stringPreferencesKey("widget_background")

/** Per-instance Glance state key holding a [WidgetThemeMode] override; unset = follow the app's own theme setting. */
internal val WIDGET_THEME_KEY = stringPreferencesKey("widget_theme")

/** Per-instance Glance state key holding the widget's [WidgetRowColor] ([name]); unset = [WidgetRowColor.NEUTRAL]. */
internal val WIDGET_ROW_COLOR_KEY = stringPreferencesKey("widget_row_color")

/** Per-instance Glance state key holding the Lists widget's chosen list ids, comma-joined; unset = every visible list. */
internal val WIDGET_LIST_IDS_KEY = stringPreferencesKey("widget_list_ids")

/** `null` means "every visible list" — the Lists widget's default. */
internal fun parseWidgetListIds(raw: String?): Set<Long>? =
    raw?.split(",")?.mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0 } }?.toSet()?.takeIf { it.isNotEmpty() }

internal fun Set<Long>.widgetListIdsStorageValue(): String = joinToString(",")

internal val DEFAULT_WIDGET_FILTER: TaskFilter = TaskFilter.Smart(SmartList.TODAY)

/** How a row's tonal container is tinted: the neutral surface role, or the task's own list colour. */
internal enum class WidgetRowColor { NEUTRAL, BY_LIST }

internal fun parseWidgetRowColor(raw: String?): WidgetRowColor =
    raw?.let { runCatching { WidgetRowColor.valueOf(it) }.getOrNull() } ?: WidgetRowColor.NEUTRAL

internal fun parseWidgetBackground(raw: String?): WidgetBackground =
    raw?.let { runCatching { WidgetBackground.valueOf(it) }.getOrNull() } ?: WidgetBackground.SOLID

/**
 * [WidgetBackground]s a widget offers. Today and Up next draw their hero (ring
 * label, time and title) straight on the surface rather than in containers, so
 * on [WidgetBackground.TRANSPARENT] it would sit unreadable on the wallpaper.
 */
internal fun WidgetKind.backgrounds(): List<WidgetBackground> = when (this) {
    WidgetKind.TODAY, WidgetKind.UP_NEXT -> WidgetBackground.entries - WidgetBackground.TRANSPARENT
    else -> WidgetBackground.entries
}

/** [raw] parsed, falling back to SOLID for a stored value [kind] no longer offers. */
internal fun parseWidgetBackground(raw: String?, kind: WidgetKind): WidgetBackground =
    parseWidgetBackground(raw).takeIf { it in kind.backgrounds() } ?: WidgetBackground.SOLID

/** `null` means "follow the app's own theme setting" — resolved by the caller against [TaskWidgetData.Ready.themeMode]. */
internal fun parseWidgetThemeOverride(raw: String?): WidgetThemeMode? =
    raw?.let { runCatching { WidgetThemeMode.valueOf(it) }.getOrNull() }

internal fun TaskWidgetData.Ready.effectiveThemeMode(override: WidgetThemeMode?): WidgetThemeMode =
    effectiveThemeMode(themeMode, override)

/** [override], or [appThemeMode] translated to its Glance counterpart — shared by every widget's data type. */
internal fun effectiveThemeMode(appThemeMode: ThemeMode, override: WidgetThemeMode?): WidgetThemeMode =
    override ?: when (appThemeMode) {
        ThemeMode.SYSTEM -> WidgetThemeMode.SYSTEM
        ThemeMode.LIGHT -> WidgetThemeMode.LIGHT
        ThemeMode.DARK -> WidgetThemeMode.DARK
    }

/** The smart lists a widget can show, in picker order. */
internal val WIDGET_SMART_LISTS = listOf(SmartList.TODAY, SmartList.UPCOMING, SmartList.OVERDUE, SmartList.ALL)

/**
 * Upper bound on rows, so the serialized RemoteViews stays well inside the
 * binder transaction limit however long the list is. Lower than the old flat
 * list's 60: each row is now wrapped in its own tonal container, so the
 * per-row view count is higher for the same visual density (calendula#214).
 */
internal const val MAX_WIDGET_ROWS = 40

internal fun TaskFilter.widgetStorageValue(): String = when (this) {
    is TaskFilter.OfList -> "list:$listId"
    is TaskFilter.OfLists -> "lists:${listIds.sorted().joinToString(",")}"
    is TaskFilter.Smart -> "smart:${list.name}"
    is TaskFilter.Tagged -> error("A tag has no widget")
    is TaskFilter.Saved -> "filter:$id"
}

/** Anything unreadable, or a smart list the widget doesn't offer, falls back to Today. */
internal fun parseWidgetFilter(raw: String?): TaskFilter {
    val value = raw ?: return DEFAULT_WIDGET_FILTER
    return when {
        value.startsWith("list:") ->
            value.removePrefix("list:").toLongOrNull()?.takeIf { it > 0 }?.let { TaskFilter.OfList(it) }
        value.startsWith("lists:") ->
            value.removePrefix("lists:").split(",").mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0 } }
                .toSet().takeIf { it.isNotEmpty() }?.let { TaskFilter.OfLists(it) }
        value.startsWith("filter:") ->
            value.removePrefix("filter:").toLongOrNull()?.takeIf { it > 0 }?.let { TaskFilter.Saved(it) }
        value.startsWith("smart:") ->
            WIDGET_SMART_LISTS.firstOrNull { it.name == value.removePrefix("smart:") }?.let { TaskFilter.Smart(it) }
        else -> null
    } ?: DEFAULT_WIDGET_FILTER
}

/** Which day a due date falls on, relative to today. */
enum class DueKind { PAST, TODAY, TOMORROW, LATER }

/** The day [due] falls on relative to [today]; an all-day due is read as its UTC date. */
internal fun dueKind(due: Instant, allDay: Boolean, today: LocalDate, zone: ZoneId): DueKind {
    val date = due.calendarDate(allDay, zone)
    return when {
        date < today -> DueKind.PAST
        date == today -> DueKind.TODAY
        date == today.plusDays(1) -> DueKind.TOMORROW
        else -> DueKind.LATER
    }
}

/** A timed task is overdue once its moment passes; an all-day one only the day after. */
internal fun isOverdue(due: Instant, allDay: Boolean, now: Instant, today: LocalDate, zone: ZoneId): Boolean =
    if (allDay) due.calendarDate(true, zone) < today else due < now

data class WidgetTaskRow(
    val taskId: Long,
    val listId: Long,
    val occurrenceStart: Instant?,
    val title: String,
    val due: Instant?,
    val isAllDay: Boolean,
    val dueKind: DueKind?,
    val isOverdue: Boolean,
    val color: Int,
)

sealed interface TaskWidgetData {
    /** No readable store yet (onboarding unfinished, permission revoked, provider gone). */
    data object Unavailable : TaskWidgetData

    data class Ready(
        val filter: TaskFilter,
        /** Names of the real lists [rows] can belong to, by list id. */
        val listNames: Map<Long, String>,
        val rows: List<WidgetTaskRow>,
        /** Group [rows] under a header per list (only meaningful with several lists). */
        val groupByList: Boolean,
        val dynamicColor: Boolean,
        val themeMode: ThemeMode,
        val use24Hour: Boolean,
        /** The saved filter's name when [filter] is one; `null` once it's deleted. */
        val filterName: String? = null,
    ) : TaskWidgetData {
        /** The one list's id, when [filter] names exactly one real list — seeds a widget's add button. */
        val singleListId: Long? = when (filter) {
            is TaskFilter.OfList -> filter.listId
            is TaskFilter.OfLists -> filter.listIds.singleOrNull()
            is TaskFilter.Smart, is TaskFilter.Tagged, is TaskFilter.Saved -> null
        }

        /** [singleListId]'s name. */
        val singleListName: String? = singleListId?.let(listNames::get)

        val overdueCount: Int = rows.count { it.isOverdue }
    }
}

/** A run of rows in the widget's list: one task, or a run's header. */
internal sealed interface WidgetSection {
    data class Header(val key: String, val name: String) : WidgetSection
    data class TaskItem(val row: WidgetTaskRow) : WidgetSection
}

/** One headed run: its header's stable [key] and display [name], and the rows under it. */
private data class WidgetRun(val key: String, val name: String, val rows: List<WidgetTaskRow>)

/** Each [WidgetRun] as its own [WidgetSection.Header] followed by its rows. */
private fun headedRuns(runs: List<WidgetRun>): List<WidgetSection> =
    runs.flatMap { run -> listOf(WidgetSection.Header(run.key, run.name)) + run.rows.map(WidgetSection::TaskItem) }

/**
 * [rows] split into headed runs: one per list when [groupByList] is on
 * (already sorted that way by [widgetRows]), otherwise one per [DueKind]
 * bucket — "Overdue" / "Today" / "Tomorrow" / "Later" / "No date" — in that
 * fixed order, since [rows] isn't due-sorted. Each run becomes one header
 * followed by its own standalone, independently coloured task pills.
 */
internal fun widgetSections(
    rows: List<WidgetTaskRow>,
    groupByList: Boolean,
    listNames: Map<Long, String>,
    dueBucketLabel: (DueKind?) -> String,
): List<WidgetSection> {
    if (groupByList) {
        val runs = mutableListOf<WidgetRun>()
        var current: MutableList<WidgetTaskRow>? = null
        for (row in rows) {
            if (current == null || runs.last().key != "list:${row.listId}") {
                current = mutableListOf()
                runs += WidgetRun("list:${row.listId}", listNames[row.listId].orEmpty(), current)
            }
            current.add(row)
        }
        return headedRuns(runs)
    }
    val buckets = rows.groupBy { it.dueKind }
    val runs = DueKind.entries.plus(null).mapNotNull { kind ->
        buckets[kind]?.takeIf { it.isNotEmpty() }
            ?.let { WidgetRun("due:${kind?.name.orEmpty()}", dueBucketLabel(kind), it) }
    }
    return headedRuns(runs)
}

/**
 * Open rows for [tasks], sorted the way the app sorts them, then grouped by list
 * when [groupByList] is on (stable, so each list's own order survives). A
 * subtask whose parent is also shown is left out, as on the list screen.
 */
internal fun widgetRows(
    tasks: List<Task>,
    comparator: Comparator<Task>,
    now: Instant,
    zone: ZoneId,
    groupByList: Boolean,
    listNames: Map<Long, String>,
): List<WidgetTaskRow> {
    val open = tasks.filterNot { it.isClosed }
    val present = open.mapTo(HashSet()) { it.taskId }
    val today = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds()).atZone(zone).toLocalDate()
    val sorted = open
        .filter { (it.parentId ?: 0L) <= 0L || it.parentId !in present }
        .sortedWith(comparator)
        .let {
            if (groupByList) {
                it.sortedWith(compareBy({ task -> listNames[task.listId].orEmpty() }, { task -> task.listId }))
            } else it
        }
    return sorted.take(MAX_WIDGET_ROWS).map { it.toWidgetRow(now, today, zone) }
}

/** This task as a [WidgetTaskRow], resolving its due bucket and overdue state against [today]. */
internal fun Task.toWidgetRow(now: Instant, today: LocalDate, zone: ZoneId): WidgetTaskRow = WidgetTaskRow(
    taskId = taskId,
    listId = listId,
    occurrenceStart = occurrenceStart,
    title = title,
    due = due,
    isAllDay = isAllDay,
    dueKind = due?.let { dueKind(it, isAllDay, today, zone) },
    isOverdue = due?.let { isOverdue(it, isAllDay, now, today, zone) } ?: false,
    color = effectiveColor,
)

/**
 * Live widget data for [filter]. Re-emits on every store change, since the
 * repository's flows observe the store; a store that can't be read (permission
 * revoked, provider uninstalled) reads as [TaskWidgetData.Unavailable].
 */
internal fun Context.taskWidgetData(filter: TaskFilter, groupByList: Boolean): Flow<TaskWidgetData> {
    val ep = widgetEntryPoint()
    val repository = ep.tasksRepository()
    return combine(
        repository.filteredTasks(filter),
        repository.taskLists(),
        ep.settingsPrefs().settings,
    ) { filtered, lists, settings ->
        val tasks = filtered.tasks
        val listNames = lists.associate { it.id to it.name }
        val data: TaskWidgetData = TaskWidgetData.Ready(
            filter = filter,
            listNames = listNames,
            rows = widgetRows(
                settings.visibleIn(filter, tasks),
                settings.sortOrder.comparator(),
                Clock.System.now(),
                ZoneId.systemDefault(),
                groupByList,
                listNames,
            ),
            groupByList = groupByList,
            dynamicColor = settings.dynamicColor,
            themeMode = settings.themeMode,
            use24Hour = settings.timeFormat.is24Hour(DateFormat.is24HourFormat(this)),
            filterName = filtered.savedFilter?.name,
        )
        data
    }.catch { emit(TaskWidgetData.Unavailable) }
}

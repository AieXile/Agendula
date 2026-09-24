package de.jeanlucmakiola.agendula.widget

import android.content.Context
import android.text.format.DateFormat
import de.jeanlucmakiola.agendula.data.prefs.ThemeMode
import de.jeanlucmakiola.agendula.data.prefs.is24Hour
import androidx.datastore.preferences.core.stringPreferencesKey
import de.jeanlucmakiola.agendula.domain.SmartList
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.calendarDate
import de.jeanlucmakiola.agendula.domain.comparator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Instant

/** Per-instance Glance state key holding the widget's [TaskFilter] ([widgetStorageValue]). */
internal val WIDGET_FILTER_KEY = stringPreferencesKey("task_filter")

internal val DEFAULT_WIDGET_FILTER: TaskFilter = TaskFilter.Smart(SmartList.TODAY)

/** The smart lists a widget can show, in picker order. */
internal val WIDGET_SMART_LISTS = listOf(SmartList.TODAY, SmartList.UPCOMING, SmartList.OVERDUE, SmartList.ALL)

/**
 * Upper bound on rows, so the serialized RemoteViews stays well inside the
 * binder transaction limit however long the list is.
 */
internal const val MAX_WIDGET_ROWS = 60

internal fun TaskFilter.widgetStorageValue(): String = when (this) {
    is TaskFilter.OfList -> "list:$listId"
    is TaskFilter.Smart -> "smart:${list.name}"
}

/** Anything unreadable, or a smart list the widget doesn't offer, falls back to Today. */
internal fun parseWidgetFilter(raw: String?): TaskFilter {
    val value = raw ?: return DEFAULT_WIDGET_FILTER
    return when {
        value.startsWith("list:") ->
            value.removePrefix("list:").toLongOrNull()?.takeIf { it > 0 }?.let { TaskFilter.OfList(it) }
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
        /** The real list's name; `null` for a smart list or a list that no longer exists. */
        val listName: String?,
        val rows: List<WidgetTaskRow>,
        val dynamicColor: Boolean,
        val themeMode: ThemeMode,
        val use24Hour: Boolean,
    ) : TaskWidgetData
}

/**
 * Open rows for [tasks], sorted the way the app sorts them. A subtask whose
 * parent is also shown is left out, as on the list screen.
 */
internal fun widgetRows(
    tasks: List<Task>,
    comparator: Comparator<Task>,
    now: Instant,
    zone: ZoneId,
): List<WidgetTaskRow> {
    val open = tasks.filterNot { it.isClosed }
    val present = open.mapTo(HashSet()) { it.taskId }
    val today = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds()).atZone(zone).toLocalDate()
    return open
        .filter { (it.parentId ?: 0L) <= 0L || it.parentId !in present }
        .sortedWith(comparator)
        .take(MAX_WIDGET_ROWS)
        .map { task ->
            WidgetTaskRow(
                taskId = task.taskId,
                occurrenceStart = task.occurrenceStart,
                title = task.title,
                due = task.due,
                isAllDay = task.isAllDay,
                dueKind = task.due?.let { dueKind(it, task.isAllDay, today, zone) },
                isOverdue = task.due?.let { isOverdue(it, task.isAllDay, now, today, zone) } ?: false,
                color = task.effectiveColor,
            )
        }
}

/**
 * Live widget data for [filter]. Re-emits on every store change, since the
 * repository's flows observe the store; a store that can't be read (permission
 * revoked, provider uninstalled) reads as [TaskWidgetData.Unavailable].
 */
internal fun Context.taskWidgetData(filter: TaskFilter): Flow<TaskWidgetData> {
    val ep = widgetEntryPoint()
    val repository = ep.tasksRepository()
    return combine(
        repository.tasks(filter),
        repository.taskLists(),
        ep.settingsPrefs().settings,
    ) { tasks, lists, settings ->
        val data: TaskWidgetData = TaskWidgetData.Ready(
            filter = filter,
            listName = (filter as? TaskFilter.OfList)?.let { f -> lists.firstOrNull { it.id == f.listId }?.name },
            rows = widgetRows(settings.visibleIn(filter, tasks), settings.sortOrder.comparator(), Clock.System.now(), ZoneId.systemDefault()),
            dynamicColor = settings.dynamicColor,
            themeMode = settings.themeMode,
            use24Hour = settings.timeFormat.is24Hour(DateFormat.is24HourFormat(this)),
        )
        data
    }.catch { emit(TaskWidgetData.Unavailable) }
}

package com.aiexile.agendula.widget

import android.content.Context
import android.text.format.DateFormat
import com.aiexile.agendula.data.prefs.ThemeMode
import com.aiexile.agendula.data.prefs.is24Hour
import com.aiexile.agendula.domain.SmartList
import com.aiexile.agendula.domain.Task
import com.aiexile.agendula.domain.TaskFilter
import com.aiexile.agendula.domain.TaskList
import com.aiexile.agendula.domain.calendarDate
import com.aiexile.agendula.domain.comparator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Instant

/** One day of the Week widget's strip. [first] is that day's earliest task, in sort order. */
internal data class WeekDay(val date: LocalDate, val count: Int, val overdueCount: Int, val first: WidgetTaskRow?)

/** One tile of the Lists widget. */
internal data class ListTile(val id: Long, val name: String, val color: Int, val openCount: Int, val acceptsWrites: Boolean)

internal data class WidgetStatsSnapshot(
    val todayDone: Int,
    val todayRemaining: Int,
    val todayTasks: List<WidgetTaskRow>,
    /** Open dated tasks, earliest due first — Up next shows the head, optionally narrowed to one list. */
    val upcoming: List<WidgetTaskRow>,
    val week: List<WeekDay>,
    val lists: List<ListTile>,
    val dynamicColor: Boolean,
    val themeMode: ThemeMode,
    val use24Hour: Boolean,
)

internal sealed interface WidgetStatsData {
    /** No readable store yet, mirroring [TaskWidgetData.Unavailable]. */
    data object Unavailable : WidgetStatsData
    data class Ready(val snapshot: WidgetStatsSnapshot) : WidgetStatsData
}

private const val MAX_TODAY_ROWS = 20

/**
 * How far through today a set of [open] and [completed] tasks says you are:
 * tasks done today (by [Task.completedAt]'s local date), tasks still open that
 * are overdue or due today (in [comparator] order), and those tasks as rows.
 */
internal fun todayProgress(
    open: List<Task>,
    completed: List<Task>,
    comparator: Comparator<Task>,
    now: Instant,
    today: LocalDate,
    zone: ZoneId,
): Triple<Int, Int, List<WidgetTaskRow>> {
    val done = completed.count { task ->
        val at = task.completedAt ?: return@count false
        at.toLocalDate(zone) == today
    }
    val remaining = open.filter { task ->
        val due = task.due ?: return@filter false
        isOverdue(due, task.isAllDay, now, today, zone) || dueKind(due, task.isAllDay, today, zone) == DueKind.TODAY
    }.sortedWith(comparator)
    return Triple(done, remaining.size, remaining.take(MAX_TODAY_ROWS).map { it.toWidgetRow(now, today, zone) })
}

/** Open dated tasks by earliest due moment, overdue included; undated tasks are skipped. */
internal fun upcoming(open: List<Task>, now: Instant, today: LocalDate, zone: ZoneId): List<WidgetTaskRow> =
    open.filter { it.due != null }.sortedBy { it.due!! }.take(MAX_UPCOMING_ROWS).map { it.toWidgetRow(now, today, zone) }

private const val MAX_UPCOMING_ROWS = 200

/**
 * Seven [WeekDay]s starting at [today]. Today's bucket folds in every overdue
 * task regardless of its own due day, since that's where the app's own
 * Overdue/Today smart lists put it; every other day matches its own due date.
 */
internal fun weekDays(open: List<Task>, comparator: Comparator<Task>, now: Instant, today: LocalDate, zone: ZoneId): List<WeekDay> =
    (0..6L).map { offset ->
        val date = today.plusDays(offset)
        val dayTasks = open.filter { task ->
            val due = task.due ?: return@filter false
            if (offset == 0L) {
                isOverdue(due, task.isAllDay, now, today, zone) || due.calendarDate(task.isAllDay, zone) == date
            } else {
                due.calendarDate(task.isAllDay, zone) == date
            }
        }
        val overdueCount = if (offset == 0L) {
            dayTasks.count { isOverdue(it.due!!, it.isAllDay, now, today, zone) }
        } else {
            0
        }
        val first = dayTasks.sortedWith(comparator).firstOrNull()?.toWidgetRow(now, today, zone)
        WeekDay(date, dayTasks.size, overdueCount, first)
    }

/** One tile per visible list: its open, non-subtask count — a subtask whose parent is also open is left out. */
internal fun listTiles(open: List<Task>, lists: List<TaskList>): List<ListTile> {
    val present = open.mapTo(HashSet()) { it.taskId }
    val counted = open.filter { (it.parentId ?: 0L) <= 0L || it.parentId !in present }
    val counts = counted.groupingBy { it.listId }.eachCount()
    return lists.filter { it.isVisible }.map { list ->
        ListTile(list.id, list.name, list.color, counts[list.id] ?: 0, list.acceptsWrites)
    }
}

private fun Instant.toLocalDate(zone: ZoneId): LocalDate =
    java.time.Instant.ofEpochMilli(toEpochMilliseconds()).atZone(zone).toLocalDate()

/**
 * Live stats for the Today, Up next, Lists and Week widgets — the ALL and
 * COMPLETED smart lists, filtered the same way the app's own smart lists are
 * ([com.aiexile.agendula.data.prefs.Settings.visibleIn]).
 */
internal fun Context.widgetStats(): Flow<WidgetStatsData> {
    val ep = widgetEntryPoint()
    val repository = ep.tasksRepository()
    return combine(
        repository.tasks(TaskFilter.Smart(SmartList.ALL)),
        repository.tasks(TaskFilter.Smart(SmartList.COMPLETED)),
        repository.taskLists(),
        ep.settingsPrefs().settings,
    ) { openRaw, completedRaw, lists, settings ->
        val open = settings.visibleIn(TaskFilter.Smart(SmartList.ALL), openRaw)
        val completed = settings.visibleIn(TaskFilter.Smart(SmartList.ALL), completedRaw)
        val now = Clock.System.now()
        val zone = ZoneId.systemDefault()
        val today = now.toLocalDate(zone)
        val comparator = settings.sortOrder.comparator()
        val (done, remaining, todayTasks) = todayProgress(open, completed, comparator, now, today, zone)
        val data: WidgetStatsData = WidgetStatsData.Ready(
            WidgetStatsSnapshot(
                todayDone = done,
                todayRemaining = remaining,
                todayTasks = todayTasks,
                upcoming = upcoming(open, now, today, zone),
                week = weekDays(open, comparator, now, today, zone),
                lists = listTiles(open, lists),
                dynamicColor = settings.dynamicColor,
                themeMode = settings.themeMode,
                use24Hour = settings.timeFormat.is24Hour(DateFormat.is24HourFormat(this)),
            ),
        )
        data
    }.catch { emit(WidgetStatsData.Unavailable) }
}

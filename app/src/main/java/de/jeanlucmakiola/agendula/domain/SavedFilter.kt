package de.jeanlucmakiola.agendula.domain

import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Instant

/** Where an open task's due date falls, relative to today. */
enum class DueWindow { OVERDUE, TODAY, UPCOMING, NO_DATE }

/** How a [SavedFilter] with several tags reads them. */
enum class TagMatch { ANY, ALL }

/**
 * A user-made view over the open tasks. An empty criterion doesn't narrow:
 * no [due] windows means any due date, no [listIds] any list, no [tags] any tag.
 * [minPriority] [Priority.NONE] lets every priority through.
 *
 * [listsElsewhere] is set when the filter also has lists in another task store.
 * With none of its lists here it then matches nothing rather than every list.
 */
data class SavedFilter(
    val id: Long,
    val name: String,
    val due: Set<DueWindow> = emptySet(),
    val listIds: Set<Long> = emptySet(),
    val tags: Set<String> = emptySet(),
    val tagMatch: TagMatch = TagMatch.ANY,
    val minPriority: Priority = Priority.NONE,
    val listsElsewhere: Boolean = false,
) {
    /** Whether the filter narrows by list at all. */
    val anyList: Boolean get() = listIds.isEmpty() && !listsElsewhere

    /** This filter without the list ids missing from [existing], e.g. lists since deleted. */
    fun withListsIn(existing: Set<Long>): SavedFilter = copy(listIds = listIds intersect existing)

    fun matches(task: Task, todayStart: Instant, todayEnd: Instant): Boolean {
        if (task.isClosed) return false
        if (listsElsewhere && listIds.isEmpty()) return false
        if (listIds.isNotEmpty() && task.listId !in listIds) return false
        if (task.priority < minPriority) return false
        if (due.isNotEmpty() && dueWindowOf(task, todayStart, todayEnd) !in due) return false
        return when {
            tags.isEmpty() -> true
            tagMatch == TagMatch.ALL -> task.tags.containsAll(tags)
            else -> task.tags.any { it in tags }
        }
    }
}

/**
 * Where [task]'s due falls against local today ([todayStart] to [todayEnd], from
 * [DayWindow] in [zone]). An all-day due is stored as UTC midnight, so it is read
 * as its calendar date first rather than compared as a raw instant.
 */
fun dueWindowOf(
    task: Task,
    todayStart: Instant,
    todayEnd: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): DueWindow {
    val due = task.due?.let { if (task.isAllDay) it.rebasedForAllDay(allDay = false, zone = zone) else it }
    return when {
        due == null -> DueWindow.NO_DATE
        due < todayStart -> DueWindow.OVERDUE
        due < todayEnd -> DueWindow.TODAY
        else -> DueWindow.UPCOMING
    }
}

/** What a task created from a [SavedFilter] starts with, so it lands in that filter. */
data class NewTaskPreset(
    val listIds: Set<Long> = emptySet(),
    val tags: List<String> = emptyList(),
    val dueDate: LocalDate? = null,
    val priority: Priority = Priority.NONE,
)

/**
 * The preset for a new task in this filter. Due prefers today, then tomorrow;
 * a filter that admits undated tasks, or only overdue ones, leaves it empty.
 */
fun SavedFilter.newTaskPreset(today: LocalDate): NewTaskPreset = NewTaskPreset(
    listIds = listIds,
    tags = when {
        tagMatch == TagMatch.ALL -> tags.sorted()
        else -> listOfNotNull(tags.minOrNull())
    },
    dueDate = when {
        due.isEmpty() || DueWindow.NO_DATE in due -> null
        DueWindow.TODAY in due -> today
        DueWindow.UPCOMING in due -> today.plusDays(1)
        else -> null
    },
    priority = minPriority,
)

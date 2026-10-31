package com.aiexile.agendula.domain

import kotlin.time.Instant

/** The built-in "smart" lists, computed from due dates rather than membership. */
enum class SmartList { ALL, TODAY, UPCOMING, OVERDUE, NO_DATE, COMPLETED }

/** What a task screen is showing: one real list, a smart list, or a tag. */
sealed interface TaskFilter {
    data class OfList(val listId: Long) : TaskFilter
    /** Several lists shown together, e.g. one widget covering more than one list. */
    data class OfLists(val listIds: Set<Long>) : TaskFilter
    data class Smart(val list: SmartList) : TaskFilter
    /** Every task carrying [tag], open and completed, across all lists. */
    data class Tagged(val tag: String) : TaskFilter
    /** A [SavedFilter] by id; the repository resolves it to its definition. */
    data class Saved(val id: Long) : TaskFilter
}

/**
 * Pure smart-list predicate. [todayStart] is local midnight today and [todayEnd]
 * local midnight tomorrow (compute with [DayWindow]). Kept side-effect-free so it
 * unit-tests with a fixed clock.
 */
object TaskFiltering {

    /** [saved] is the definition behind a [TaskFilter.Saved]; without one it matches nothing. */
    fun matches(
        task: Task,
        filter: TaskFilter,
        todayStart: Instant,
        todayEnd: Instant,
        saved: SavedFilter? = null,
    ): Boolean =
        when (filter) {
            is TaskFilter.OfList -> task.listId == filter.listId
            is TaskFilter.OfLists -> task.listId in filter.listIds
            is TaskFilter.Smart -> matchesSmart(task, filter.list, todayStart, todayEnd)
            is TaskFilter.Tagged -> filter.tag in task.tags
            is TaskFilter.Saved -> saved?.takeIf { it.id == filter.id }?.matches(task, todayStart, todayEnd) ?: false
        }

    /** Whether [task] carries every one of [tags]; an empty set matches everything. */
    fun hasAllTags(task: Task, tags: Set<String>): Boolean = task.tags.containsAll(tags)

    private fun matchesSmart(task: Task, list: SmartList, todayStart: Instant, todayEnd: Instant): Boolean =
        when (list) {
            SmartList.COMPLETED -> task.isCompleted
            SmartList.ALL -> !task.isClosed
            SmartList.NO_DATE -> !task.isClosed && dueWindowOf(task, todayStart, todayEnd) == DueWindow.NO_DATE
            SmartList.OVERDUE -> !task.isClosed && dueWindowOf(task, todayStart, todayEnd) == DueWindow.OVERDUE
            SmartList.TODAY -> !task.isClosed && dueWindowOf(task, todayStart, todayEnd) == DueWindow.TODAY
            SmartList.UPCOMING -> !task.isClosed && dueWindowOf(task, todayStart, todayEnd) == DueWindow.UPCOMING
        }
}

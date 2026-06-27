package de.jeanlucmakiola.agendula.domain

import kotlin.time.Instant

/** The built-in "smart" lists, computed from due dates rather than membership. */
enum class SmartList { ALL, TODAY, UPCOMING, OVERDUE, NO_DATE, COMPLETED }

/** What a task screen is showing: one real list, or a smart list. */
sealed interface TaskFilter {
    data class OfList(val listId: Long) : TaskFilter
    data class Smart(val list: SmartList) : TaskFilter
}

/**
 * Pure smart-list predicate. [todayStart] is local midnight today and [todayEnd]
 * local midnight tomorrow (compute with [DayWindow]). Kept side-effect-free so it
 * unit-tests with a fixed clock.
 */
object TaskFiltering {

    fun matches(task: Task, filter: TaskFilter, todayStart: Instant, todayEnd: Instant): Boolean =
        when (filter) {
            is TaskFilter.OfList -> task.listId == filter.listId
            is TaskFilter.Smart -> matchesSmart(task, filter.list, todayStart, todayEnd)
        }

    private fun matchesSmart(task: Task, list: SmartList, todayStart: Instant, todayEnd: Instant): Boolean {
        val due = task.due
        return when (list) {
            SmartList.COMPLETED -> task.isCompleted
            SmartList.ALL -> !task.isClosed
            SmartList.NO_DATE -> !task.isClosed && due == null
            SmartList.OVERDUE -> !task.isClosed && due != null && due < todayStart
            SmartList.TODAY -> !task.isClosed && due != null && due >= todayStart && due < todayEnd
            SmartList.UPCOMING -> !task.isClosed && due != null && due >= todayEnd
        }
    }
}

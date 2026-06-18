package de.jeanlucmakiola.floret.domain

import kotlin.time.Instant

/** A due-date bucket a task list groups its rows under. */
enum class TaskSection { OVERDUE, TODAY, UPCOMING, NO_DATE, COMPLETED }

/** A non-empty run of tasks under one [TaskSection], already in display order. */
data class SectionedTasks(val section: TaskSection, val tasks: List<Task>)

/**
 * Group already-sorted tasks into due-date sections (the standard task-app
 * presentation). Pure + side-effect-free so it unit-tests with a fixed clock,
 * mirroring [TaskFiltering] / [DayWindow]. Completed tasks always fall to the
 * [TaskSection.COMPLETED] bucket regardless of their due date.
 *
 * [todayStart] is local midnight today and [todayEnd] local midnight tomorrow
 * (compute with [DayWindow]). Only non-empty sections are returned, in the
 * fixed order Overdue → Today → Upcoming → No date → Completed.
 */
object TaskSections {

    fun of(tasks: List<Task>, todayStart: Instant, todayEnd: Instant): List<SectionedTasks> {
        val bySection = tasks.groupBy { sectionOf(it, todayStart, todayEnd) }
        return TaskSection.entries.mapNotNull { section ->
            bySection[section]?.takeIf { it.isNotEmpty() }?.let { SectionedTasks(section, it) }
        }
    }

    fun sectionOf(task: Task, todayStart: Instant, todayEnd: Instant): TaskSection {
        if (task.isCompleted) return TaskSection.COMPLETED
        val due = task.due ?: return TaskSection.NO_DATE
        return when {
            due < todayStart -> TaskSection.OVERDUE
            due < todayEnd -> TaskSection.TODAY
            else -> TaskSection.UPCOMING
        }
    }
}

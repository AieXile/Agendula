package com.aiexile.agendula.domain

/** Default task ordering, extracted so it's unit-testable without the repository. */
object TaskSorting {
    /** Open before completed; within each: due-soonest (none last), priority, title. */
    val DEFAULT: Comparator<Task> = compareBy(
        { it.isCompleted },
        { it.due == null },
        { it.due?.toEpochMilliseconds() ?: Long.MAX_VALUE },
        { -it.priority.ordinal },
        { it.title.lowercase() },
    )
}

/** The orders a user can pick for a task list. Open tasks always come before closed ones. */
enum class TaskSortOrder { DUE, PRIORITY, TITLE, CREATED }

fun TaskSortOrder.comparator(): Comparator<Task> = when (this) {
    TaskSortOrder.DUE -> TaskSorting.DEFAULT
    TaskSortOrder.PRIORITY -> compareBy<Task>(
        { it.isCompleted },
        { -it.priority.ordinal },
        { it.due?.toEpochMilliseconds() ?: Long.MAX_VALUE },
        { it.title.lowercase() },
    )
    TaskSortOrder.TITLE -> compareBy({ it.isCompleted }, { it.title.lowercase() })
    TaskSortOrder.CREATED -> compareBy<Task>(
        { it.isCompleted },
        { -(it.created?.toEpochMilliseconds() ?: 0L) },
        { it.title.lowercase() },
    )
}

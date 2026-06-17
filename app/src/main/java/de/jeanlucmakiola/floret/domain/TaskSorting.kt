package de.jeanlucmakiola.floret.domain

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

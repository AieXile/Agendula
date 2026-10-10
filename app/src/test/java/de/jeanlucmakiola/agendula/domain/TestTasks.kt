package de.jeanlucmakiola.agendula.domain

import kotlin.time.Instant

/** Builds a [Task] for tests with sensible defaults; override what matters. */
fun testTask(
    id: Long = 1,
    listId: Long = 1,
    title: String = "task",
    status: TaskStatus = TaskStatus.NEEDS_ACTION,
    priority: Priority = Priority.NONE,
    due: Instant? = null,
    isAllDay: Boolean = false,
    completedAt: Instant? = null,
    parentId: Long? = null,
    tags: List<String> = emptyList(),
): Task = Task(
    taskId = id,
    listId = listId,
    title = title,
    description = null,
    location = null,
    url = null,
    priority = priority,
    status = status,
    percentComplete = null,
    start = null,
    due = due,
    isAllDay = isAllDay,
    timeZone = null,
    completedAt = completedAt,
    listColor = 0,
    taskColor = null,
    listName = null,
    accountName = null,
    parentId = parentId,
    isRecurring = false,
    occurrenceStart = null,
    distanceFromCurrent = 0,
    created = null,
    lastModified = null,
    tags = tags,
)

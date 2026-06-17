package de.jeanlucmakiola.floret.data.tasks

import de.jeanlucmakiola.floret.data.tasks.TasksContract.Instances
import de.jeanlucmakiola.floret.data.tasks.TasksContract.Lists
import de.jeanlucmakiola.floret.data.tasks.TasksContract.Tasks
import de.jeanlucmakiola.floret.domain.Task
import de.jeanlucmakiola.floret.domain.TaskList
import de.jeanlucmakiola.floret.domain.priorityFromICal
import de.jeanlucmakiola.floret.domain.statusFromInt
import kotlin.time.Instant

/** Maps a provider row (via [ColumnReader]) to a domain model. Pure + testable. */
object TaskMapper {

    fun task(r: ColumnReader): Task {
        fun instant(name: String): Instant? =
            r.getLong(name)?.let { Instant.fromEpochMilliseconds(it) }

        val instanceId = r.getLong(Tasks.ID) ?: 0L
        return Task(
            id = instanceId,
            taskId = r.getLong(Instances.TASK_ID) ?: instanceId,
            listId = r.getLong(Tasks.LIST_ID) ?: 0L,
            title = r.getString(Tasks.TITLE).orEmpty(),
            description = r.getString(Tasks.DESCRIPTION),
            location = r.getString(Tasks.LOCATION),
            url = r.getString(Tasks.URL),
            priority = priorityFromICal(r.getInt(Tasks.PRIORITY)),
            status = statusFromInt(r.getInt(Tasks.STATUS)),
            percentComplete = r.getInt(Tasks.PERCENT_COMPLETE),
            start = instant(Instances.INSTANCE_START),
            due = instant(Instances.INSTANCE_DUE),
            isAllDay = r.getBoolean(Tasks.IS_ALLDAY),
            timeZone = r.getString(Tasks.TZ),
            completedAt = instant(Tasks.COMPLETED),
            listColor = r.getInt(Tasks.LIST_COLOR) ?: 0,
            taskColor = r.getInt(Tasks.TASK_COLOR),
            listName = r.getString(Tasks.LIST_NAME),
            accountName = r.getString(Tasks.ACCOUNT_NAME),
            parentId = r.getLong(Tasks.PARENT_ID),
            isRecurring = r.getBoolean(Instances.IS_RECURRING),
            distanceFromCurrent = r.getInt(Instances.DISTANCE_FROM_CURRENT),
            created = instant(Tasks.CREATED),
            lastModified = instant(Tasks.LAST_MODIFIED),
        )
    }

    fun taskList(r: ColumnReader): TaskList = TaskList(
        id = r.getLong(Lists.ID) ?: 0L,
        name = r.getString(Lists.NAME).orEmpty(),
        color = r.getInt(Lists.COLOR) ?: 0,
        accountName = r.getString(Lists.ACCOUNT_NAME).orEmpty(),
        accountType = r.getString(Lists.ACCOUNT_TYPE).orEmpty(),
        isSynced = r.getBoolean(Lists.SYNC_ENABLED),
        isVisible = r.getBoolean(Lists.VISIBLE),
        owner = r.getString(Lists.OWNER),
    )
}

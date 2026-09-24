package de.jeanlucmakiola.agendula.data.tasks

import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Instances
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Lists
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Tasks
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.domain.export.ExportTask
import de.jeanlucmakiola.agendula.domain.priorityFromICal
import de.jeanlucmakiola.agendula.domain.statusFromInt
import kotlin.time.Instant

/** Maps a provider row (via [ColumnReader]) to a domain model. Pure + testable. */
object TaskMapper {

    fun task(r: ColumnReader): Task {
        fun instant(name: String): Instant? =
            r.getLong(name)?.let { Instant.fromEpochMilliseconds(it) }

        val rowId = r.getLong(Tasks.ID) ?: 0L
        // Derived from the rule columns rather than the `is_recurring` column
        // alone: that column only exists from OpenTasks 1.4.0 (DB 23) and is
        // absent on tasks.org's bundled provider (DB 22), where reading it
        // would silently report every recurring task as one-off — and route
        // its edits onto the series anchor.
        val recurring = r.getString(Tasks.RRULE) != null ||
            r.getString(Tasks.RDATE) != null ||
            r.getBoolean(Instances.IS_RECURRING)
        return Task(
            taskId = r.getLong(Instances.TASK_ID) ?: rowId,
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
            isRecurring = recurring,
            occurrenceStart = if (recurring) occurrenceAnchor(r) else null,
            distanceFromCurrent = r.getInt(Instances.DISTANCE_FROM_CURRENT),
            created = instant(Tasks.CREATED),
            lastModified = instant(Tasks.LAST_MODIFIED),
            seriesId = if (recurring) r.getLong(Instances.TASK_ID) ?: rowId else null,
            recurrenceRule = r.getString(Tasks.RRULE),
        )
    }

    /**
     * The occurrence's `RECURRENCE-ID` anchor.
     *
     * `instance_original_time` is the provider's own name for it and is set on
     * every occurrence of a recurring task, so it is read first. It is absent on
     * older provider schemas, where the fallbacks reconstruct the same value: a
     * DTSTART-anchored series instantiates each occurrence at its start, and a
     * series carrying only DUE anchors on the due date instead.
     */
    fun occurrenceAnchor(r: ColumnReader): Instant? =
        (
            r.getLong(Instances.INSTANCE_ORIGINAL_TIME)
                ?: r.getLong(Instances.INSTANCE_START)
                ?: r.getLong(Instances.INSTANCE_DUE)
            )?.let { Instant.fromEpochMilliseconds(it) }

    /**
     * Maps a row of the **`tasks` table** — a master task, not an occurrence.
     *
     * Export reads there rather than from `instances` on purpose: in the instances
     * view a recurring task appears once per occurrence with its times already
     * resolved and no rule attached, so exporting from it would write the same
     * task many times over and drop the RRULE that produced them. Here each task
     * appears exactly once, carrying the rule itself.
     */
    fun exportTask(r: ColumnReader): ExportTask {
        fun instant(name: String): Instant? =
            r.getLong(name)?.let { Instant.fromEpochMilliseconds(it) }

        return ExportTask(
            taskId = r.getLong(Tasks.ID) ?: 0L,
            uid = r.getString(Tasks.UID),
            title = r.getString(Tasks.TITLE).orEmpty(),
            description = r.getString(Tasks.DESCRIPTION),
            location = r.getString(Tasks.LOCATION),
            url = r.getString(Tasks.URL),
            priority = priorityFromICal(r.getInt(Tasks.PRIORITY)),
            status = statusFromInt(r.getInt(Tasks.STATUS)),
            percentComplete = r.getInt(Tasks.PERCENT_COMPLETE),
            // The task's own columns, not the instance view's resolved ones.
            start = instant(Tasks.DTSTART),
            due = instant(Tasks.DUE),
            isAllDay = r.getBoolean(Tasks.IS_ALLDAY),
            completedAt = instant(Tasks.COMPLETED),
            created = instant(Tasks.CREATED),
            lastModified = instant(Tasks.LAST_MODIFIED),
            rrule = r.getString(Tasks.RRULE),
            rdate = r.getString(Tasks.RDATE),
            parentId = r.getLong(Tasks.PARENT_ID)?.takeIf { it > 0 },
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

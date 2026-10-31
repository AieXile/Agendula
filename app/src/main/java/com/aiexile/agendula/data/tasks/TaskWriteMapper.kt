package com.aiexile.agendula.data.tasks

import com.aiexile.agendula.data.tasks.TasksContract.Alarm
import com.aiexile.agendula.data.tasks.TasksContract.Lists
import com.aiexile.agendula.data.tasks.TasksContract.Properties
import com.aiexile.agendula.data.tasks.TasksContract.Tasks
import com.aiexile.agendula.domain.TaskForm
import com.aiexile.agendula.domain.toICal
import kotlin.time.Instant

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/** Floor to UTC midnight when [allDay], else pass through unchanged. */
private fun Instant.forAllDay(allDay: Boolean): Instant =
    if (!allDay) this
    else Instant.fromEpochMilliseconds(
        Math.floorDiv(toEpochMilliseconds(), MILLIS_PER_DAY) * MILLIS_PER_DAY,
    )

/**
 * Turns a [TaskForm] / mutation into a name→value map. Pure (no ContentValues),
 * so it unit-tests on the JVM; [AndroidTasksDataSource] converts the map to
 * ContentValues. `null` values are written as SQL NULL (clears the column).
 */
object TaskWriteMapper {

    fun taskValues(form: TaskForm, tzId: String): Map<String, Any?> = buildMap {
        put(Tasks.TITLE, form.title.trim())
        put(Tasks.LIST_ID, form.listId)
        put(Tasks.DESCRIPTION, form.description?.trim()?.ifBlank { null })
        put(Tasks.LOCATION, form.location?.trim()?.ifBlank { null })
        put(Tasks.URL, form.url?.trim()?.ifBlank { null })
        put(Tasks.PRIORITY, form.priority.toICal())
        // Keep completion in sync with progress whenever the form carries a
        // percent (the Progress field was used). The provider already auto-
        // completes at 100% but won't reopen below it — left to itself it strands
        // a task "done at 75%" — so we set status both ways. COMPLETED is only
        // cleared on reopen; at 100% we leave it for the provider to fill/keep so
        // re-saving a finished task doesn't churn its completion timestamp. When
        // no percent is present the standalone complete toggle stays authoritative.
        val percent = form.percentComplete?.coerceIn(0, 100)
        put(Tasks.PERCENT_COMPLETE, percent)
        when {
            percent == null -> Unit
            percent >= 100 -> put(Tasks.STATUS, TasksContract.STATUS_COMPLETED)
            percent > 0 -> {
                put(Tasks.STATUS, TasksContract.STATUS_IN_PROCESS)
                put(Tasks.COMPLETED, null)
            }
            else -> {
                put(Tasks.STATUS, TasksContract.STATUS_NEEDS_ACTION)
                put(Tasks.COMPLETED, null)
            }
        }
        put(Tasks.IS_ALLDAY, if (form.isAllDay) 1 else 0)
        // All-day tasks are date-only in iCalendar. The provider reads them back
        // through DateTime.toAllDay(), which drops the time-of-day and resolves the
        // remaining date against UTC — so a local-midnight instant lands on the
        // previous day for anyone west of UTC. Pin all-day values to UTC midnight.
        put(Tasks.DTSTART, form.start?.forAllDay(form.isAllDay)?.toEpochMilliseconds())
        put(Tasks.DUE, form.due?.forAllDay(form.isAllDay)?.toEpochMilliseconds())
        // DUE and DURATION are mutually exclusive. The provider's Validating
        // processor evaluates the *merged* row (supplied values over the stored
        // ones), so writing DUE onto a task that already carries a DURATION throws
        // "Only one of DUE or DURATION must be supplied." Clear it alongside.
        put(Tasks.DURATION, null)
        put(Tasks.PARENT_ID, form.parentId)
        // The provider treats a null tz as local time; set it explicitly for
        // timed tasks so the stored instant is unambiguous across zones.
        val timed = !form.isAllDay && (form.start != null || form.due != null)
        put(Tasks.TZ, if (timed) tzId else null)
    }

    /** [taskValues] plus the rule — for a plain task or a series master, never an occurrence. */
    fun seriesValues(form: TaskForm, tzId: String): Map<String, Any?> =
        taskValues(form, tzId) + (Tasks.RRULE to form.rrule?.removePrefix("RRULE:")?.ifBlank { null })

    /**
     * Values for an update through the *instances* URI (a recurring occurrence).
     * The provider clones the row into an override and strips list/recurrence
     * fields as it goes, so LIST_ID and PARENT_ID are dropped here rather than
     * written and silently ignored — moving one occurrence between lists or
     * parents isn't a thing the override model expresses.
     */
    fun instanceValues(form: TaskForm, tzId: String): Map<String, Any?> =
        taskValues(form, tzId) - Tasks.LIST_ID - Tasks.PARENT_ID

    fun completionValues(completed: Boolean, nowMillis: Long): Map<String, Any?> =
        if (completed) {
            mapOf(
                Tasks.STATUS to TasksContract.STATUS_COMPLETED,
                Tasks.PERCENT_COMPLETE to 100,
                Tasks.COMPLETED to nowMillis,
            )
        } else {
            mapOf(
                Tasks.STATUS to TasksContract.STATUS_NEEDS_ACTION,
                Tasks.PERCENT_COMPLETE to null,
                Tasks.COMPLETED to null,
            )
        }

    /**
     * A reminder for [taskId], as an Alarm property row. The provider's validator
     * requires MINUTES_BEFORE, REFERENCE (non-negative) and ALARM_TYPE on every
     * write, so all three are always present.
     */
    fun alarmValues(taskId: Long, minutesBeforeDue: Int, fromStart: Boolean = false): Map<String, Any?> = mapOf(
        Properties.TASK_ID to taskId,
        Properties.MIMETYPE to Alarm.MIMETYPE,
        Alarm.MINUTES_BEFORE to minutesBeforeDue,
        Alarm.REFERENCE to if (fromStart) Alarm.REFERENCE_START else Alarm.REFERENCE_DUE,
        Alarm.ALARM_TYPE to Alarm.TYPE_MESSAGE,
    )

    fun categoryValues(taskId: Long, name: String): Map<String, Any?> = mapOf(
        Properties.TASK_ID to taskId,
        Properties.MIMETYPE to TasksContract.Category.MIMETYPE,
        TasksContract.Category.NAME to name,
    )

    /** The user-owned columns of a list — what an edit is allowed to change. */
    fun listValues(name: String, color: Int): Map<String, Any?> = mapOf(
        Lists.NAME to name.trim(),
        Lists.COLOR to color,
    )

    fun localListValues(name: String, color: Int): Map<String, Any?> = listValues(name, color) + mapOf(
        Lists.ACCOUNT_NAME to TasksContract.LOCAL_ACCOUNT_NAME,
        Lists.ACCOUNT_TYPE to TasksContract.LOCAL_ACCOUNT_TYPE,
        Lists.VISIBLE to 1,
        Lists.SYNC_ENABLED to 1,
    )
}

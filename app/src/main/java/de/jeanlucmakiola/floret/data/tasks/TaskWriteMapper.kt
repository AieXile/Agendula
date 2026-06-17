package de.jeanlucmakiola.floret.data.tasks

import de.jeanlucmakiola.floret.data.tasks.TasksContract.Lists
import de.jeanlucmakiola.floret.data.tasks.TasksContract.Tasks
import de.jeanlucmakiola.floret.domain.TaskForm
import de.jeanlucmakiola.floret.domain.toICal

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
        put(Tasks.PRIORITY, form.priority.toICal())
        put(Tasks.IS_ALLDAY, if (form.isAllDay) 1 else 0)
        put(Tasks.DTSTART, form.start?.toEpochMilliseconds())
        put(Tasks.DUE, form.due?.toEpochMilliseconds())
        put(Tasks.PARENT_ID, form.parentId)
        // The provider treats a null tz as local time; set it explicitly for
        // timed tasks so the stored instant is unambiguous across zones.
        val timed = !form.isAllDay && (form.start != null || form.due != null)
        put(Tasks.TZ, if (timed) tzId else null)
    }

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

    fun localListValues(name: String, color: Int): Map<String, Any?> = mapOf(
        Lists.NAME to name.trim(),
        Lists.COLOR to color,
        Lists.ACCOUNT_NAME to TasksContract.LOCAL_ACCOUNT_NAME,
        Lists.ACCOUNT_TYPE to TasksContract.LOCAL_ACCOUNT_TYPE,
        Lists.VISIBLE to 1,
        Lists.SYNC_ENABLED to 1,
    )
}

package com.aiexile.agendula.data.tasks.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.aiexile.agendula.domain.TaskReminder
import com.aiexile.agendula.domain.ical.ICalParser
import com.aiexile.agendula.domain.ical.ICalValues
import kotlin.time.Instant

/** Reads and writes over `task_alarms`. */
@Dao
interface TaskAlarmDao {

    /** Every reminder in the store, for one scheduler pass. */
    @Query("SELECT * FROM task_alarms ORDER BY id")
    fun all(): List<TaskAlarmEntity>

    @Query("SELECT * FROM task_alarms WHERE task_id = :taskId ORDER BY id")
    fun forTask(taskId: Long): List<TaskAlarmEntity>

    /** The reminders of several tasks in one query — one resource's rows, for an upload. */
    @Query("SELECT * FROM task_alarms WHERE task_id IN (:taskIds) ORDER BY id")
    fun forTasks(taskIds: List<Long>): List<TaskAlarmEntity>

    @Query("SELECT * FROM task_alarms WHERE id = :id")
    fun entity(id: Long): TaskAlarmEntity?

    @Insert
    fun insert(alarm: TaskAlarmEntity): Long

    @Update
    fun update(alarm: TaskAlarmEntity): Int

    @Query("DELETE FROM task_alarms WHERE id = :id")
    fun delete(id: Long): Int

    @Query("DELETE FROM task_alarms WHERE task_id = :taskId")
    fun deleteForTask(taskId: Long): Int

    /** See [TaskEntity.remindersOff]. */
    @Query("UPDATE tasks SET reminders_off = :off WHERE id = :taskId")
    fun setRemindersOff(taskId: Long, off: Boolean): Int

    /** The tasks whose alarms were turned off elsewhere — "none", not "the default". */
    @Query("SELECT id FROM tasks WHERE reminders_off = 1")
    fun remindersOff(): List<Long>

    /** Set the task's only reminder, or clear every one with `null`. */
    @Transaction
    fun replaceForTask(taskId: Long, alarm: TaskAlarmEntity?) {
        deleteForTask(taskId)
        alarm?.let { insert(it.copy(id = 0, taskId = taskId)) }
    }

    /**
     * Make the task's reminders [wanted], touching only what differs.
     *
     * ⚠️ A diff, not a delete-and-reinsert. A row that is still wanted keeps its
     * [TaskAlarmEntity.uid] and its [TaskAlarmEntity.raw] — which [TaskReminder]
     * carries neither of, so a rewrite from it would give every save a new
     * alarm identity on the server and throw away another client's `VALARM` the
     * user never touched.
     *
     * Duplicates count: two identical leads are two rows.
     *
     * @return whether anything changed.
     */
    @Transaction
    fun syncForTask(taskId: Long, wanted: List<TaskReminder>): Boolean {
        val unmatched = wanted.map { it.lead }.toMutableList()
        var changed = false
        forTask(taskId).forEach { row ->
            if (!unmatched.remove(row.asReminder())) {
                delete(row.id)
                changed = true
            }
        }
        unmatched.forEach {
            insert(it.toAlarmEntity(taskId))
            changed = true
        }
        // A reminder of its own again, so the "turned off elsewhere" no longer holds.
        if (wanted.isNotEmpty()) setRemindersOff(taskId, false)
        return changed
    }

    /**
     * Replace the task's reminders with what a download carried.
     *
     * Incoming alarms are matched to the rows already here by uid first, then by
     * lead, so a reminder the server merely re-described keeps its row rather
     * than being churned into a new one.
     *
     * A download that takes away every alarm the task had marks it
     * [TaskEntity.remindersOff]; one that carries alarms clears that.
     */
    @Transaction
    fun replaceFromServer(taskId: Long, incoming: List<TaskAlarmEntity>) {
        val existing = forTask(taskId).toMutableList()
        val hadAny = existing.isNotEmpty()
        val placed = incoming.map { alarm ->
            val match = existing.firstOrNull { it.uid == alarm.uid }
                ?: existing.firstOrNull { it.asReminder() == alarm.asReminder() }
            match?.let(existing::remove)
            alarm.copy(id = match?.id ?: 0, taskId = taskId)
        }
        existing.forEach { delete(it.id) }
        placed.forEach { if (it.id == 0L) insert(it) else update(it) }
        when {
            incoming.isNotEmpty() -> setRemindersOff(taskId, false)
            hadAny -> setRemindersOff(taskId, true)
        }
    }

    /**
     * Copy [fromTaskId]'s reminders onto [toTaskId].
     *
     * [sameResource] is for an override forked inside the same series — the
     * same `VTODO` UID, so the alarms keep their identity. Anything else is a
     * different task on the server and gets fresh uids: two tasks naming one
     * alarm is an identity no other client expects.
     */
    @Transaction
    fun copyToTask(fromTaskId: Long, toTaskId: Long, sameResource: Boolean) {
        forTask(fromTaskId).forEach { insert(if (sameResource) it.copy(id = 0, taskId = toTaskId) else it.freshCopy(toTaskId)) }
    }
}

/** What the row means to the scheduler and the editor, without its identity. */
fun TaskAlarmEntity.asReminder() = TaskReminder(minutesBefore, reference == AlarmReference.START)

/** The inverse of [asReminder]: a new row, with an identity of its own. */
fun TaskReminder.toAlarmEntity(taskId: Long) = TaskAlarmEntity(
    taskId = taskId,
    minutesBefore = minutesBefore,
    reference = if (fromStart) AlarmReference.START else AlarmReference.DUE,
)

/** The same reminder on another task: what it means, never who it is on the server. */
fun TaskAlarmEntity.freshCopy(taskId: Long) =
    TaskAlarmEntity(taskId = taskId, minutesBefore = minutesBefore, reference = reference, message = message)

/**
 * The `ACKNOWLEDGED` stamp of the `VALARM` the row was read from, if any.
 *
 * Read off [TaskAlarmEntity.raw] rather than kept in a column: it is the other
 * client's bookkeeping, and it changes only when a download replaces the row.
 */
fun TaskAlarmEntity.acknowledgedAt(): Instant? {
    val text = raw ?: return null
    if (!text.contains("ACKNOWLEDGED", ignoreCase = true)) return null
    val property = runCatching { ICalParser.parse(text) }.getOrNull()?.property("ACKNOWLEDGED") ?: return null
    return (ICalValues.parseTime(property) as? ICalValues.TimeValue.Timed)?.instant
}

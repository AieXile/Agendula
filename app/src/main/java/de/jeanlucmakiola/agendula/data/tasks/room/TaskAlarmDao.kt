package de.jeanlucmakiola.agendula.data.tasks.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** Reads and writes over `task_alarms`. */
@Dao
interface TaskAlarmDao {

    /** Every reminder in the store, for one scheduler pass. */
    @Query("SELECT * FROM task_alarms ORDER BY id")
    fun all(): List<TaskAlarmEntity>

    @Query("SELECT * FROM task_alarms WHERE task_id = :taskId ORDER BY id")
    fun forTask(taskId: Long): List<TaskAlarmEntity>

    @Insert
    fun insert(alarm: TaskAlarmEntity): Long

    @Query("DELETE FROM task_alarms WHERE id = :id")
    fun delete(id: Long): Int

    @Query("DELETE FROM task_alarms WHERE task_id = :taskId")
    fun deleteForTask(taskId: Long): Int

    /** Set the task's only reminder, or clear every one with `null`. */
    @Transaction
    fun replaceForTask(taskId: Long, alarm: TaskAlarmEntity?) = replaceAllForTask(taskId, listOfNotNull(alarm))

    /**
     * Replace every reminder on the task. The rows that land are always new ones —
     * ids are cleared so alarms lifted off another task (forking an occurrence
     * copies the master's) insert instead of colliding.
     */
    @Transaction
    fun replaceAllForTask(taskId: Long, alarms: List<TaskAlarmEntity>) {
        deleteForTask(taskId)
        alarms.forEach { insert(it.copy(id = 0, taskId = taskId)) }
    }

    /**
     * Set the task's editable reminder — its last before-due one — or clear it
     * with `null`. Start-relative and additional reminders stay as they are.
     */
    @Transaction
    fun replaceEditable(taskId: Long, alarm: TaskAlarmEntity?) {
        forTask(taskId).lastOrNull { it.reference == AlarmReference.DUE }?.let { delete(it.id) }
        alarm?.let { insert(it.copy(id = 0, taskId = taskId)) }
    }
}

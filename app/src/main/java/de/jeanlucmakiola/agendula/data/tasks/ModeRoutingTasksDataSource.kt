package de.jeanlucmakiola.agendula.data.tasks

import de.jeanlucmakiola.agendula.data.tasks.room.RoomTasksDataSource
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskForm
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.domain.export.ExportTask
import javax.inject.Provider
import kotlin.time.Instant

/**
 * Routes every call to the store [StorageMode] selects.
 *
 * Per-call rather than bound once: the mode is a setting the user can change
 * while the process lives, and [StorageModeHolder] pushes the new value into
 * [ProviderResolver] without rebuilding the object graph. Both delegates are
 * singletons, so this chooses between two existing objects.
 *
 * Room versus a third-party ContentProvider — nothing else.
 */
class ModeRoutingTasksDataSource(
    private val resolver: ProviderResolver,
    private val room: Provider<RoomTasksDataSource>,
    private val external: Provider<AndroidTasksDataSource>,
) : TasksDataSource {

    private fun active(): TasksDataSource =
        when (resolver.mode()) {
            StorageMode.OWN -> room.get()
            StorageMode.EXTERNAL -> external.get()
        }

    override fun taskLists(): List<TaskList> = active().taskLists()
    override fun tasks(query: TaskQuery): List<Task> = active().tasks(query)
    override fun task(taskId: Long): Task? = active().task(taskId)
    override fun task(taskId: Long, occurrenceStart: Instant?): Task? = active().task(taskId, occurrenceStart)
    override fun updateSeries(seriesId: Long, occurrenceStart: Instant, form: TaskForm, dueReminders: List<Int>?) =
        active().updateSeries(seriesId, occurrenceStart, form, dueReminders)
    override fun splitSeries(seriesId: Long, occurrenceStart: Instant, form: TaskForm, dueReminders: List<Int>?): Long =
        active().splitSeries(seriesId, occurrenceStart, form, dueReminders)
    override fun deleteOccurrence(seriesId: Long, occurrenceStart: Instant) =
        active().deleteOccurrence(seriesId, occurrenceStart)
    override fun deleteFollowing(seriesId: Long, occurrenceStart: Instant) =
        active().deleteFollowing(seriesId, occurrenceStart)
    override fun subtasks(parentTaskId: Long): List<Task> = active().subtasks(parentTaskId)
    override fun insertTask(form: TaskForm, reminders: List<TaskReminder>?): Long = active().insertTask(form, reminders)
    override fun updateTask(taskId: Long, form: TaskForm, dueReminders: List<Int>?) =
        active().updateTask(taskId, form, dueReminders)

    override fun updateInstance(taskId: Long, occurrenceStart: Instant, form: TaskForm, dueReminders: List<Int>?) =
        active().updateInstance(taskId, occurrenceStart, form, dueReminders)

    override fun setAlarm(taskId: Long, minutesBeforeDue: Int?) = active().setAlarm(taskId, minutesBeforeDue)
    override fun alarms(): Map<Long, TaskReminder> = active().alarms()
    override fun reminders(): Map<Long, List<TaskReminder>> = active().reminders()
    override fun setReminders(taskId: Long, reminders: List<TaskReminder>) =
        active().setReminders(taskId, reminders)
    override fun exportTasks(listId: Long): List<ExportTask> = active().exportTasks(listId)
    override fun setCompleted(taskId: Long, completed: Boolean) = active().setCompleted(taskId, completed)
    override fun setCancelled(taskId: Long, cancelled: Boolean) = active().setCancelled(taskId, cancelled)

    override fun setCompletedInstance(taskId: Long, occurrenceStart: Instant, completed: Boolean) =
        active().setCompletedInstance(taskId, occurrenceStart, completed)

    override fun setCancelledInstance(taskId: Long, occurrenceStart: Instant, cancelled: Boolean) =
        active().setCancelledInstance(taskId, occurrenceStart, cancelled)

    override fun deleteTask(taskId: Long) = active().deleteTask(taskId)
    override fun createLocalList(name: String, color: Int): Long = active().createLocalList(name, color)
    override fun updateList(listId: Long, name: String, color: Int) = active().updateList(listId, name, color)
    override fun deleteList(listId: Long) = active().deleteList(listId)
    override fun reorderLists(listIds: List<Long>) = active().reorderLists(listIds)
    /**
     * Unlike every other method here, an observer is registered once and then
     * *held* — so it cannot be routed per call, and would otherwise stay bound to
     * whichever store was active when the flow started. Switching stores in
     * Settings would then leave every open screen listening to the store it is no
     * longer reading from.
     *
     * So the registration moves with the mode, and the switch itself counts as a
     * change: the data underneath every live flow has just been replaced.
     */
    override fun registerObserver(onChange: () -> Unit): AutoCloseable {
        val lock = Any()
        var closed = false
        var handle: AutoCloseable? = runCatching { active().registerObserver(onChange) }.getOrNull()

        val modeHandle = resolver.onModeChanged {
            synchronized(lock) {
                if (!closed) {
                    handle?.let { runCatching { it.close() } }
                    handle = runCatching { active().registerObserver(onChange) }.getOrNull()
                }
            }
            onChange()
        }
        return AutoCloseable {
            synchronized(lock) {
                closed = true
                modeHandle.close()
                handle?.let { runCatching { it.close() } }
                handle = null
            }
        }
    }
}

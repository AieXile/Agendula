package de.jeanlucmakiola.floret.data.tasks

import de.jeanlucmakiola.floret.domain.Task
import de.jeanlucmakiola.floret.domain.TaskForm
import de.jeanlucmakiola.floret.domain.TaskList

/** What to fetch from the provider. Smart-list date logic is applied above this. */
data class TaskQuery(
    val listId: Long? = null,
    val includeCompleted: Boolean = false,
)

/**
 * Domain-shaped, synchronous seam over the tasks ContentResolver. Returns parsed
 * lists so [TasksRepositoryImpl] can be tested with a fake on the JVM. All Cursor
 * and ContentObserver handling lives in [AndroidTasksDataSource].
 */
interface TasksDataSource {
    fun taskLists(): List<TaskList>
    fun tasks(query: TaskQuery): List<Task>
    fun task(taskId: Long): Task?
    fun subtasks(parentTaskId: Long): List<Task>

    fun insertTask(form: TaskForm): Long
    fun updateTask(taskId: Long, form: TaskForm)
    fun setCompleted(taskId: Long, completed: Boolean)
    fun deleteTask(taskId: Long)
    fun createLocalList(name: String, color: Int): Long

    /** Observe any change to tasks/lists; [onChange] fires on a background thread. */
    fun registerObserver(onChange: () -> Unit): AutoCloseable
}

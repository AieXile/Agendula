package de.jeanlucmakiola.agendula.data.tasks

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Instances
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Lists
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Tasks
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskForm
import de.jeanlucmakiola.agendula.domain.TaskList
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only class that knows about the ContentResolver, [TasksContract] and the
 * active authority. Everything else works through [TasksDataSource] / domain
 * models, so swapping the provider (Posture B) never reaches above this file.
 */
@Singleton
class AndroidTasksDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerResolver: ProviderResolver,
) : TasksDataSource {

    private val resolver: ContentResolver get() = context.contentResolver

    private fun authority(): String =
        providerResolver.resolve()?.authority ?: throw ProviderUnavailableException()

    private fun taskUri(authority: String, taskId: Long): Uri =
        ContentUris.withAppendedId(TasksContract.tasksUri(authority), taskId)

    // --- reads ----------------------------------------------------------------

    override fun taskLists(): List<TaskList> {
        val uri = TasksContract.listsUri(authority())
        val sort = "${Lists.ACCOUNT_NAME}, ${Lists.NAME}"
        return resolver.query(uri, TaskProjections.LISTS, null, null, sort)?.use { c ->
            val reader = CursorColumnReader(c)
            buildList { while (c.moveToNext()) add(TaskMapper.taskList(reader)) }
        } ?: emptyList()
    }

    override fun tasks(query: TaskQuery): List<Task> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        query.listId?.let {
            clauses += "${Tasks.LIST_ID} = ?"
            args += it.toString()
        }
        if (!query.includeCompleted) clauses += "${Tasks.IS_CLOSED} = 0"
        val selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        return queryInstances(selection, args.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    override fun task(taskId: Long): Task? {
        val rows = queryInstances("${Instances.TASK_ID} = ?", arrayOf(taskId.toString()))
        return rows.firstOrNull { it.distanceFromCurrent == 0 } ?: rows.firstOrNull()
    }

    override fun subtasks(parentTaskId: Long): List<Task> =
        queryInstances("${Tasks.PARENT_ID} = ?", arrayOf(parentTaskId.toString()))

    private fun queryInstances(selection: String?, args: Array<String>?): List<Task> {
        val uri = TasksContract.instancesUri(authority())
        // projection = null (all columns): the instances view differs across
        // provider versions (e.g. tasks.org's bundled OpenTasks has no
        // `is_recurring` / `distance_from_current`). The by-name mapper reads
        // whatever is present and nulls the rest; the repository re-sorts, so we
        // don't depend on a provider sort column either.
        return resolver.query(uri, null, selection, args, null)
            ?.use { c ->
                val reader = CursorColumnReader(c)
                buildList { while (c.moveToNext()) add(TaskMapper.task(reader)) }
            } ?: emptyList()
    }

    // --- writes ---------------------------------------------------------------

    override fun insertTask(form: TaskForm): Long {
        val values = TaskWriteMapper.taskValues(form, ZoneId.systemDefault().id)
        val uri = resolver.insert(TasksContract.tasksUri(authority()), values.toContentValues())
            ?: throw TaskWriteFailedException("insert task")
        return uri.lastPathSegment?.toLongOrNull() ?: throw TaskWriteFailedException("insert task: no id")
    }

    override fun updateTask(taskId: Long, form: TaskForm) {
        val values = TaskWriteMapper.taskValues(form, ZoneId.systemDefault().id)
        val rows = resolver.update(taskUri(authority(), taskId), values.toContentValues(), null, null)
        if (rows == 0) throw TaskWriteFailedException("update task $taskId")
    }

    override fun setCompleted(taskId: Long, completed: Boolean) {
        val values = TaskWriteMapper.completionValues(completed, System.currentTimeMillis())
        val rows = resolver.update(taskUri(authority(), taskId), values.toContentValues(), null, null)
        if (rows == 0) throw TaskWriteFailedException("complete task $taskId")
    }

    override fun deleteTask(taskId: Long) {
        resolver.delete(taskUri(authority(), taskId), null, null)
    }

    override fun createLocalList(name: String, color: Int): Long {
        val uri = TasksContract.asSyncAdapter(
            TasksContract.listsUri(authority()),
            TasksContract.LOCAL_ACCOUNT_NAME,
            TasksContract.LOCAL_ACCOUNT_TYPE,
        )
        val values = TaskWriteMapper.localListValues(name, color)
        val result = resolver.insert(uri, values.toContentValues())
            ?: throw TaskWriteFailedException("create local list")
        return result.lastPathSegment?.toLongOrNull() ?: throw TaskWriteFailedException("create local list: no id")
    }

    // --- observation ----------------------------------------------------------

    override fun registerObserver(onChange: () -> Unit): AutoCloseable {
        val provider = providerResolver.resolve() ?: return AutoCloseable { }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        resolver.registerContentObserver(TasksContract.instancesUri(provider.authority), true, observer)
        resolver.registerContentObserver(TasksContract.listsUri(provider.authority), true, observer)
        return AutoCloseable { resolver.unregisterContentObserver(observer) }
    }

    private fun Map<String, Any?>.toContentValues(): ContentValues {
        val cv = ContentValues(size)
        for ((key, value) in this) when (value) {
            null -> cv.putNull(key)
            is Long -> cv.put(key, value)
            is Int -> cv.put(key, value)
            is Boolean -> cv.put(key, if (value) 1 else 0)
            is String -> cv.put(key, value)
            else -> cv.put(key, value.toString())
        }
        return cv
    }
}

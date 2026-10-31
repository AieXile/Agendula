package com.aiexile.agendula.data.tasks.transfer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.aiexile.agendula.data.di.ExternalStore
import com.aiexile.agendula.data.di.IoDispatcher
import com.aiexile.agendula.data.tasks.ProviderResolver
import com.aiexile.agendula.domain.TaskReminder
import com.aiexile.agendula.data.tasks.TasksDataSource
import com.aiexile.agendula.data.tasks.room.toAlarmEntity
import com.aiexile.agendula.data.tasks.room.TaskEntity
import com.aiexile.agendula.data.tasks.room.TaskListEntity
import com.aiexile.agendula.data.tasks.room.TasksDatabase
import com.aiexile.agendula.domain.TaskList
import com.aiexile.agendula.domain.export.ExportTask
import com.aiexile.agendula.domain.normalizeTags
import com.aiexile.agendula.domain.toICal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** How much one copy moved. */
data class TransferCounts(val lists: Int, val tasks: Int, val alarms: Int)

/** The outcome of [ExternalImport.run]. */
sealed interface TransferResult {
    /** The external store holds no list to copy — nothing was written. */
    data object NothingToCopy : TransferResult

    data class Copied(val counts: TransferCounts) : TransferResult

    /** Nothing landed: the transaction rolled back and the source is untouched. */
    data class Failed(val cause: Throwable) : TransferResult
}

/**
 * Copies an external provider's tasks (OpenTasks, tasks.org) into Agendula's own
 * Room store, once, when the user asks for it in Settings → Storage.
 *
 * This is the upgrade path 1.0.0 needs and [com.aiexile.agendula.data
 * .tasks.legacy.OneShotImport] does not provide: that one moves a *bundled dmfs
 * provider's* SQLite file, which no released version ever shipped, so every
 * existing install's tasks are in a third-party provider instead. Without this
 * the only way onto the new store is to retype everything by hand.
 *
 * **A copy, not a sync, and deliberately one-directional.** The source is left
 * exactly as it is — whatever syncs it (DAVx5 and friends) keeps doing so, and
 * the two sets of rows drift apart from the moment this finishes. The reverse
 * direction is not offered: writing a *list* into a third-party provider means
 * impersonating its sync adapter, and the external store is already the one that
 * can sync.
 *
 * **What does not come across**, because the read seam is
 * [TasksDataSource.exportTasks] and that is shaped for iCalendar output:
 * per-occurrence `RECURRENCE-ID` overrides (a series arrives as its master plus
 * its rule, so an edited single occurrence reverts to the series' own values),
 * `EXDATE`, `CLASS`, `DURATION`, the per-task timezone, and a task's exact
 * `PRIORITY` digit — [com.aiexile.agendula.domain.Priority] buckets 1–4 as
 * HIGH, so a `PRIORITY:3` lands as `1`. Nothing the app itself displays is lost.
 *
 * Task `uid`s *are* preserved, which is what lets these rows be re-attached to a
 * CalDAV collection once sync lands rather than duplicating server-side.
 */
@Singleton
class ExternalImport @Inject constructor(
    @ExternalStore private val external: Provider<TasksDataSource>,
    private val resolver: ProviderResolver,
    private val database: TasksDatabase,
    private val dataStore: DataStore<Preferences>,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Whether a copy has already succeeded. The UI uses this to stop offering the
     * action, because a second run would duplicate every row: the rows it writes
     * are ordinary tasks afterwards, indistinguishable from ones typed by hand, so
     * there is nothing to reconcile a re-run against.
     */
    val hasRun: Flow<Boolean> = dataStore.data.map { it[TRANSFER_DONE] ?: false }

    /**
     * What a copy would move, for the confirmation to name real numbers — or
     * `null` when there is no readable external provider to copy from.
     *
     * Counts masters, the same rows [run] writes, so the number the user agrees to
     * is the number they get.
     */
    suspend fun preview(): TransferCounts? = withContext(io) {
        val provider = resolver.resolveExternal() ?: return@withContext null
        if (!resolver.hasPermission(provider)) return@withContext null
        runCatching {
            val source = external.get()
            val lists = source.taskLists()
            val tasks = lists.sumOf { source.exportTasks(it.id).size }
            TransferCounts(lists = lists.size, tasks = tasks, alarms = source.reminders().values.sumOf { it.size })
        }.getOrNull()
    }

    /**
     * Reads the external store, then writes everything into Room in **one
     * transaction with verified counts** — the same discipline as the legacy
     * import, for the same reason: a partial copy is worse than none, because the
     * user cannot tell which half is missing.
     *
     * The flag is written only after the transaction commits. A crash in between
     * leaves the rows in place and the action still on offer, which duplicates on
     * a second run — the lesser of the two evils, since the alternative is
     * claiming a copy that never happened.
     */
    suspend fun run(): TransferResult = withContext(io) {
        val snapshot = runCatching { read() }
            .getOrElse { return@withContext TransferResult.Failed(it) }
        if (snapshot.lists.isEmpty()) return@withContext TransferResult.NothingToCopy
        val counts = runCatching {
            database.runInTransaction<TransferCounts> {
                val baseline = tableCounts()
                val written = write(snapshot)
                verify(written, baseline)
                written
            }
        }.getOrElse { return@withContext TransferResult.Failed(it) }
        markDone()
        TransferResult.Copied(counts)
    }

    // --- reading the external provider ----------------------------------------

    private fun read(): Snapshot {
        val source = external.get()
        val lists = source.taskLists()
        return Snapshot(
            lists = lists,
            tasksByList = lists.associate { it.id to source.exportTasks(it.id) },
            alarms = source.reminders(),
        )
    }

    // --- writing into Room ----------------------------------------------------

    /**
     * Provider row ids are the source's own, and Room mints its own on insert, so
     * `parentId` is remapped through the ids the inserts hand back. Tasks go in
     * with their parent cleared and a second pass sets it, because a parent may
     * sort after its child.
     *
     * Every list arrives as **device-only** (`account_id IS NULL`), including one
     * that sat under a CalDAV account in the provider: the account belongs to the
     * sync app, not to us, and claiming it here would suggest Agendula syncs it.
     */
    private fun write(snapshot: Snapshot): TransferCounts {
        val listDao = database.taskLists()
        val taskDao = database.tasks()
        val alarmDao = database.alarms()

        val listIds = snapshot.lists.associate { list ->
            list.id to listDao.insert(
                TaskListEntity(
                    name = list.name,
                    color = list.color,
                    accountId = null,
                    isVisible = list.isVisible,
                    isSynced = false,
                    owner = list.owner,
                ),
            )
        }

        val taskIds = mutableMapOf<Long, Long>()
        val inserted = mutableListOf<Pair<ExportTask, TaskEntity>>()
        // A `RECURRENCE-ID` override reaches us as another master-shaped row
        // sharing its series' UID, which the unique index would reject and take
        // the whole copy down with it. A fresh uid costs that row nothing it
        // still has.
        val seen = mutableSetOf<Pair<Long, String>>()

        for ((sourceListId, tasks) in snapshot.tasksByList) {
            val listId = listIds[sourceListId] ?: continue
            for (task in tasks) {
                val uid = task.uid?.takeIf { seen.add(listId to it) } ?: UUID.randomUUID().toString()
                val entity = task.toEntity(listId, uid)
                val newId = taskDao.insert(entity)
                taskIds[task.taskId] = newId
                inserted += task to entity.copy(id = newId)
            }
        }

        for ((task, entity) in inserted) {
            val parentId = task.parentId?.let(taskIds::get) ?: continue
            taskDao.update(entity.copy(parentId = parentId))
        }

        var alarmCount = 0
        for ((sourceTaskId, reminders) in snapshot.alarms) {
            val taskId = taskIds[sourceTaskId] ?: continue
            reminders.forEach { alarmDao.insert(it.toAlarmEntity(taskId)) }
            alarmCount += reminders.size
        }

        return TransferCounts(lists = listIds.size, tasks = taskIds.size, alarms = alarmCount)
    }

    private fun ExportTask.toEntity(listId: Long, uid: String) = TaskEntity(
        listId = listId,
        uid = uid,
        title = title,
        description = description,
        location = location,
        url = url,
        categories = normalizeTags(tags),
        status = status,
        percentComplete = percentComplete,
        completedAt = completedAt,
        priority = priority.toICal(),
        dtstart = start,
        due = due,
        isAllDay = isAllDay,
        rrule = rrule,
        rdate = rdate,
        createdAt = created,
        lastModified = lastModified,
    )

    private fun verify(written: TransferCounts, before: TransferCounts) {
        val after = tableCounts()
        check(after.lists - before.lists == written.lists) {
            "list count mismatch: ${after.lists - before.lists} != ${written.lists}"
        }
        check(after.tasks - before.tasks == written.tasks) {
            "task count mismatch: ${after.tasks - before.tasks} != ${written.tasks}"
        }
        check(after.alarms - before.alarms == written.alarms) {
            "alarm count mismatch: ${after.alarms - before.alarms} != ${written.alarms}"
        }
    }

    private fun tableCounts() = TransferCounts(
        lists = count("task_lists"),
        tasks = count("tasks"),
        alarms = count("task_alarms"),
    )

    private fun count(table: String): Int =
        database.query("SELECT COUNT(*) FROM $table", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    private suspend fun markDone() {
        dataStore.edit { it[TRANSFER_DONE] = true }
    }

    /** Clears the guard so the action is offered again. Test and support hook. */
    suspend fun clearCompletion() {
        dataStore.edit { it.remove(TRANSFER_DONE) }
    }

    private class Snapshot(
        val lists: List<TaskList>,
        val tasksByList: Map<Long, List<ExportTask>>,
        val alarms: Map<Long, List<TaskReminder>>,
    )

    private companion object {
        val TRANSFER_DONE = booleanPreferencesKey("external_copy_done")
    }
}

package de.jeanlucmakiola.agendula.data.tasks.legacy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.agendula.data.tasks.CursorColumnReader
import de.jeanlucmakiola.agendula.data.tasks.room.AlarmReference
import de.jeanlucmakiola.agendula.data.tasks.room.TaskAlarmEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TaskEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TaskListEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TasksDatabase
import de.jeanlucmakiola.agendula.domain.PRIORITY_NONE
import de.jeanlucmakiola.agendula.domain.normalizeTags
import de.jeanlucmakiola.agendula.domain.statusFromInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/** How much one import moved. */
data class ImportCounts(val lists: Int, val tasks: Int, val alarms: Int)

/** The outcome of [OneShotImport.runIfNeeded] or [OneShotImport.reimportFromArchive]. */
sealed interface ImportResult {
    /** The DataStore flag was already set; nothing was read. */
    data object AlreadyDone : ImportResult

    /** No legacy database on disk — a fresh install, or one already archived. */
    data object NothingToImport : ImportResult

    data class Imported(val counts: ImportCounts) : ImportResult

    /** Nothing landed: the transaction rolled back and the source is untouched. */
    data class Failed(val cause: Throwable) : ImportResult
}

/**
 * Moves a v0.3.x install's tasks out of the bundled dmfs provider's SQLite file
 * and into Room, once.
 *
 * The file is opened read-only and directly — no provider, no ContentResolver —
 * so this keeps working after `:provider` is deleted. Everything lands in one
 * Room transaction with verified counts, so a failure leaves Room exactly as it
 * was and the source file exactly where it was.
 *
 * dmfs accounts are not carried over: every list is imported as a device-only
 * list (`account_id IS NULL`), including one that sat under a real account —
 * only reachable if the user had pointed DAVx5 at our authority. Their task
 * `uid`s are preserved, which is what lets those rows be re-attached to an
 * account once sync lands.
 */
@Singleton
class OneShotImport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: TasksDatabase,
    private val dataStore: DataStore<Preferences>,
) {

    /** Whether the import has run. Set before the rename, so both guards hold. */
    val isDone: Flow<Boolean> = dataStore.data.map { it[IMPORT_DONE] ?: false }

    /**
     * Whether the last attempt failed and the archived source is still sitting
     * there unread.
     *
     * Recorded because the alternative is what this class used to do: return an
     * [ImportResult.Failed] that every caller dropped on the floor, leaving an
     * upgrading user with an empty app, no message, and their tasks in a file
     * only a developer could find. Settings → Storage offers the retry.
     */
    val lastAttemptFailed: Flow<Boolean> = dataStore.data.map { it[IMPORT_FAILED] ?: false }

    /**
     * Steps 1–8 of the plan: archive `databases/tasks.db`, import it, record
     * completion. Safe to call on every launch.
     *
     * The archive happens *before* the import, and the import always replaces, so
     * that every point this can be killed at re-enters correctly:
     *
     * - killed after the rename, before the import — the next run finds the
     *   archive, imports it, and nothing is lost;
     * - killed after the import commits, before the flag is written — the next
     *   run truncates and re-imports the same archive, so the result is the same
     *   rather than doubled.
     *
     * Renaming last would leave that second window open: the flag would be unset
     * and `tasks.db` still in place, and the next launch would import it a second
     * time on top of the first. That is the window the plan's "guarded by a
     * DataStore flag *and* by the rename" is meant to close, and only this order
     * actually closes it.
     */
    suspend fun runIfNeeded(): ImportResult = withContext(Dispatchers.IO) {
        if (isDone.first()) return@withContext ImportResult.AlreadyDone
        val source = archivedSource() ?: run {
            markDone()
            return@withContext ImportResult.NothingToImport
        }
        val counts = runCatching { importFrom(source, replaceExisting = true) }
            .getOrElse { return@withContext recordFailure(it) }
        markDone()
        ImportResult.Imported(counts)
    }

    /**
     * The rollback path: re-run against the archived `tasks.db.imported`,
     * truncating the Room tables first so a second attempt replaces rather than
     * merges. Reached by a targeted fix release, not by the app on its own.
     */
    suspend fun reimportFromArchive(): ImportResult = withContext(Dispatchers.IO) {
        val source = archivedSource() ?: return@withContext ImportResult.NothingToImport
        val counts = runCatching { importFrom(source, replaceExisting = true) }
            .getOrElse { return@withContext recordFailure(it) }
        markDone()
        ImportResult.Imported(counts)
    }

    /**
     * The legacy database as `tasks.db.imported`, archiving it first if it is
     * still under its live name. `null` when there is nothing to import.
     */
    private fun archivedSource(): File? {
        val archive = context.getDatabasePath(ARCHIVE_NAME)
        if (archive.exists()) return archive
        val live = context.getDatabasePath(LEGACY_NAME)
        if (!live.exists()) return null
        return if (archive(live)) archive else live
    }

    /** Clears the completion flag so [runIfNeeded] will import again. */
    suspend fun clearCompletion() {
        dataStore.edit { it.remove(IMPORT_DONE) }
    }

    /**
     * Steps 2–6 against an arbitrary dmfs database: read it read-only, then write
     * everything in one Room transaction whose counts are verified before it
     * commits. Blocking — call it off the main thread.
     */
    fun importFrom(source: File, replaceExisting: Boolean = false): ImportCounts {
        val snapshot = SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY)
            .use(::read)
        return database.runInTransaction<ImportCounts> {
            if (replaceExisting) truncate()
            val baseline = tableCounts()
            val written = write(snapshot)
            verify(written, baseline)
            written
        }
    }

    // --- reading the dmfs file ------------------------------------------------

    private fun read(db: SQLiteDatabase): LegacySnapshot {
        val lists = mutableListOf<LegacyList>()
        db.rawQuery("SELECT * FROM Lists ORDER BY _id", null).use { cursor ->
            val r = CursorColumnReader(cursor)
            while (cursor.moveToNext()) {
                val id = r.getLong("_id") ?: continue
                lists += LegacyList(
                    id = id,
                    entity = TaskListEntity(
                        name = r.getString("list_name").orEmpty(),
                        color = r.getInt("list_color") ?: 0,
                        accountId = null,
                        isVisible = r.getBoolean("visible"),
                        isSynced = r.getBoolean("sync_enabled"),
                        owner = r.getString("list_owner"),
                    ),
                )
            }
        }

        val rows = mutableListOf<LegacyTaskRow>()
        db.rawQuery("SELECT * FROM Tasks WHERE _deleted IS NULL OR _deleted = 0 ORDER BY _id", null)
            .use { cursor ->
                val r = CursorColumnReader(cursor)
                while (cursor.moveToNext()) {
                    val id = r.getLong("_id") ?: continue
                    rows += LegacyTaskRow(
                        id = id,
                        listId = r.getLong("list_id") ?: continue,
                        parentId = r.getLong("parent_id"),
                        masterId = r.getLong("original_instance_id"),
                        recurrenceId = r.instant("original_instance_time"),
                        entity = TaskEntity(
                            listId = 0,
                            uid = r.getString("_uid") ?: UUID.randomUUID().toString(),
                            title = r.getString("title"),
                            description = r.getString("description"),
                            location = r.getString("location"),
                            url = r.getString("url"),
                            color = r.getInt("task_color"),
                            status = statusFromInt(r.getInt("status")),
                            percentComplete = r.getInt("percent_complete"),
                            completedAt = r.instant("completed"),
                            priority = r.getInt("priority") ?: PRIORITY_NONE,
                            classification = r.getInt("class"),
                            dtstart = r.instant("dtstart"),
                            due = r.instant("due"),
                            duration = r.getString("duration"),
                            isAllDay = r.getBoolean("is_allday"),
                            timezone = r.getString("tz"),
                            rrule = r.getString("rrule"),
                            rdate = r.getString("rdate"),
                            exdate = r.getString("exdate"),
                            createdAt = r.instant("created"),
                            lastModified = r.instant("last_modified"),
                        ),
                    )
                }
            }

        val alarms = mutableListOf<LegacyAlarm>()
        val categories = mutableMapOf<Long, MutableList<String>>()
        db.rawQuery("SELECT task_id, mimetype, data0, data1, data2 FROM Properties ORDER BY property_id", null)
            .use { cursor ->
                val r = CursorColumnReader(cursor)
                while (cursor.moveToNext()) {
                    val taskId = r.getLong("task_id") ?: continue
                    if (r.getString("mimetype") == CATEGORY_MIMETYPE) {
                        r.getString("data1")?.let { categories.getOrPut(taskId, ::mutableListOf) += it }
                        continue
                    }
                    if (r.getString("mimetype") != ALARM_MIMETYPE) continue
                    val minutes = r.getString("data0")?.trim()?.toIntOrNull() ?: continue
                    alarms += LegacyAlarm(
                        taskId = taskId,
                        minutesBefore = minutes,
                        reference = if (r.getString("data1")?.trim() == REFERENCE_START) {
                            AlarmReference.START
                        } else {
                            AlarmReference.DUE
                        },
                        message = r.getString("data2"),
                    )
                }
            }

        return LegacySnapshot(lists, rows, alarms, categories.mapValues { normalizeTags(it.value) })
    }

    // --- writing into Room ----------------------------------------------------

    /**
     * dmfs `list_id`, `parent_id` and `original_instance_id` are old row ids, and
     * Room mints its own on insert, so every one of them is remapped through the
     * ids the inserts hand back. Tasks are inserted with their links cleared and
     * a second pass sets them, because a parent may be a higher `_id` than its
     * child.
     */
    private fun write(snapshot: LegacySnapshot): ImportCounts {
        val listDao = database.taskLists()
        val taskDao = database.tasks()
        val alarmDao = database.alarms()

        val listIds = snapshot.lists.associate { it.id to listDao.insert(it.entity) }

        // A task whose list is missing is already invisible in dmfs — its tasks
        // view inner-joins Lists — so dropping it loses nothing the user could see.
        val importable = snapshot.tasks.filter { it.listId in listIds }
        val importableIds = importable.mapTo(mutableSetOf()) { it.id }
        val taskIds = mutableMapOf<Long, Long>()
        val inserted = mutableListOf<Pair<LegacyTaskRow, TaskEntity>>()
        val seen = mutableSetOf<Triple<Long, String, Instant?>>()

        for (row in importable) {
            val listId = listIds.getValue(row.listId)
            val overrides = row.masterId != null && row.masterId in importableIds
            val recurrenceId = row.recurrenceId.takeIf { overrides }
            // A duplicate (list, uid, recurrence) would abort the whole import on
            // the unique index; a fresh uid costs the row nothing it still has.
            val uid = row.entity.uid.takeIf { seen.add(Triple(listId, it, recurrenceId)) }
                ?: UUID.randomUUID().toString()
            val entity = row.entity.copy(
                listId = listId,
                uid = uid,
                recurrenceId = recurrenceId,
                categories = snapshot.categories[row.id].orEmpty(),
            )
            val newId = taskDao.insert(entity)
            taskIds[row.id] = newId
            inserted += row to entity.copy(id = newId)
        }

        for ((row, entity) in inserted) {
            val parentId = row.parentId?.let(taskIds::get)
            val masterId = row.masterId?.let(taskIds::get)
            if (parentId == null && masterId == null) continue
            taskDao.update(entity.copy(parentId = parentId, masterId = masterId))
        }

        var alarmCount = 0
        for (alarm in snapshot.alarms) {
            val taskId = taskIds[alarm.taskId] ?: continue
            alarmDao.insert(
                TaskAlarmEntity(
                    taskId = taskId,
                    minutesBefore = alarm.minutesBefore,
                    reference = alarm.reference,
                    message = alarm.message,
                ),
            )
            alarmCount++
        }

        return ImportCounts(lists = listIds.size, tasks = taskIds.size, alarms = alarmCount)
    }

    private fun verify(written: ImportCounts, before: ImportCounts) {
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

    /** Dropping the lists takes their tasks and alarms with them, by cascade. */
    private fun truncate() {
        val listDao = database.taskLists()
        listDao.lists().forEach { listDao.delete(it.list.id) }
    }

    private fun tableCounts() = ImportCounts(
        lists = count("task_lists"),
        tasks = count("tasks"),
        alarms = count("task_alarms"),
    )

    private fun count(table: String): Int =
        database.query("SELECT COUNT(*) FROM $table", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    // --- the source file ------------------------------------------------------

    /**
     * Renames the dmfs file, sidecars included, to `tasks.db.imported`. Never
     * deletes it: for one release it is the only way back if the import turns out
     * to be wrong on someone's device.
     */
    private fun archive(source: File): Boolean {
        val target = File(source.parentFile, ARCHIVE_NAME)
        if (!source.renameTo(target)) return false
        for (suffix in SIDECARS) {
            val sidecar = File(source.path + suffix)
            if (sidecar.exists()) sidecar.renameTo(File(target.path + suffix))
        }
        return true
    }

    /**
     * Leaves a breadcrumb the UI can act on, and one in logcat for a bug report.
     * The completion flag is deliberately *not* set: the next launch retries on
     * its own, and the archived source is still where it was.
     */
    private suspend fun recordFailure(cause: Throwable): ImportResult.Failed {
        Log.e(TAG, "Importing the legacy task database failed; source left in place", cause)
        dataStore.edit { it[IMPORT_FAILED] = true }
        return ImportResult.Failed(cause)
    }

    private suspend fun markDone() {
        dataStore.edit {
            it[IMPORT_DONE] = true
            it.remove(IMPORT_FAILED)
        }
    }

    private fun CursorColumnReader.instant(name: String): Instant? =
        getLong(name)?.let(Instant::fromEpochMilliseconds)

    companion object {
        const val LEGACY_NAME = "tasks.db"
        const val ARCHIVE_NAME = "tasks.db.imported"

        private const val ALARM_MIMETYPE = "vnd.android.cursor.item/alarm"
        private const val CATEGORY_MIMETYPE = "vnd.android.cursor.item/category"
        private const val REFERENCE_START = "2"
        private val SIDECARS = listOf("-journal", "-wal", "-shm")
        private val IMPORT_DONE = booleanPreferencesKey("legacy_import_done")
        private val IMPORT_FAILED = booleanPreferencesKey("legacy_import_failed")
        private const val TAG = "OneShotImport"
    }
}

private class LegacySnapshot(
    val lists: List<LegacyList>,
    val tasks: List<LegacyTaskRow>,
    val alarms: List<LegacyAlarm>,
    /** Tags by legacy task `_id`. */
    val categories: Map<Long, List<String>>,
)

private class LegacyList(val id: Long, val entity: TaskListEntity)

private class LegacyTaskRow(
    val id: Long,
    val listId: Long,
    val parentId: Long?,
    val masterId: Long?,
    val recurrenceId: Instant?,
    val entity: TaskEntity,
)

private class LegacyAlarm(
    val taskId: Long,
    val minutesBefore: Int,
    val reference: AlarmReference,
    val message: String?,
)

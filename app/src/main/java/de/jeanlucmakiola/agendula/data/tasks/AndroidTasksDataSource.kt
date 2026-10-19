package de.jeanlucmakiola.agendula.data.tasks

import android.content.ContentProviderOperation
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
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Properties
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Tasks
import de.jeanlucmakiola.agendula.data.tasks.room.SPLIT_COUNT_CEILING
import de.jeanlucmakiola.agendula.data.tasks.room.TaskFormWriter
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskForm
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.domain.export.ExportTask
import de.jeanlucmakiola.agendula.domain.normalizeTags
import de.jeanlucmakiola.agendula.domain.recurrence.ExpansionWindow
import de.jeanlucmakiola.agendula.domain.recurrence.RecurrenceExpander
import de.jeanlucmakiola.agendula.domain.recurrence.RecurrenceSpec
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

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

    override fun task(taskId: Long, occurrenceStart: Instant?): Task? {
        if (occurrenceStart == null) return task(taskId)
        val rows = queryInstances("${Instances.TASK_ID} = ?", arrayOf(taskId.toString()))
        return rows.firstOrNull { it.occurrenceStart == occurrenceStart } ?: task(taskId)
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
        val tasks = resolver.query(uri, null, selection, args, null)
            ?.use { c ->
                val reader = CursorColumnReader(c)
                buildList { while (c.moveToNext()) add(TaskMapper.task(reader)) }
            } ?: emptyList()
        if (tasks.isEmpty()) return tasks
        val tags = categories(tasks.map { it.taskId })
        return tasks.map { task -> tags[task.taskId]?.let { task.copy(tags = it) } ?: task }
    }

    override fun exportTasks(listId: Long): List<ExportTask> {
        // projection = null for the same reason queryInstances uses it: the tasks
        // table's shape varies across provider versions, and the by-name mapper
        // reads what's there.
        val uri = TasksContract.tasksUri(authority())
        val tasks = resolver.query(
            uri,
            null,
            // _deleted marks a row awaiting a sync round-trip. It's gone as far as
            // the user is concerned, so exporting it would resurrect deleted tasks
            // in the backup.
            "${Tasks.LIST_ID} = ? AND (${Tasks.DELETED} IS NULL OR ${Tasks.DELETED} = 0)",
            arrayOf(listId.toString()),
            null,
        )?.use { c ->
            val reader = CursorColumnReader(c)
            buildList { while (c.moveToNext()) add(TaskMapper.exportTask(reader)) }
        } ?: emptyList()
        if (tasks.isEmpty()) return tasks
        val tags = categories(tasks.map { it.taskId })
        return tasks.map { task -> tags[task.taskId]?.let { task.copy(tags = it) } ?: task }
    }

    // --- writes ---------------------------------------------------------------

    override fun insertTask(form: TaskForm, reminders: List<TaskReminder>?): Long {
        val values = TaskWriteMapper.seriesValues(form, ZoneId.systemDefault().id)
        // One batch, so a refused tag or alarm leaves no task behind for a retry
        // to duplicate.
        val authority = authority()
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(TasksContract.tasksUri(authority))
                .withValues(values.toContentValues())
                .build(),
        )
        normalizeTags(form.tags).forEach { tag ->
            ops += ContentProviderOperation.newInsert(TasksContract.propertiesUri(authority))
                .withValues(TaskWriteMapper.categoryValues(0, tag).toContentValues().apply { remove(Properties.TASK_ID) })
                .withValueBackReference(Properties.TASK_ID, 0)
                .build()
        }
        reminders.orEmpty().forEach { reminder ->
            ops += ContentProviderOperation.newInsert(TasksContract.propertiesUri(authority))
                .withValues(
                    TaskWriteMapper.alarmValues(0, reminder.minutesBefore, reminder.fromStart)
                        .toContentValues().apply { remove(Properties.TASK_ID) },
                )
                .withValueBackReference(Properties.TASK_ID, 0)
                .build()
        }
        val results = runCatching { resolver.applyBatch(authority, ops) }
            .getOrElse { throw TaskWriteFailedException("insert task", it) }
        return results.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull()
            ?: throw TaskWriteFailedException("insert task: no id")
    }

    override fun updateTask(taskId: Long, form: TaskForm, dueReminders: List<Int>?) {
        val values = TaskWriteMapper.seriesValues(form, ZoneId.systemDefault().id)
        val rows = resolver.update(taskUri(authority(), taskId), values.toContentValues(), null, null)
        if (rows == 0) throw TaskWriteFailedException("update task $taskId")
        setCategories(taskId, form.tags)
        writeDueReminders(taskId, dueReminders)
    }

    /** [leads] as the task's before-due reminders; a start-relative one from elsewhere is kept. */
    private fun writeDueReminders(taskId: Long, leads: List<Int>?) {
        if (leads == null) return
        val kept = alarmRows(taskId).map { it.second }.filter { it.fromStart }
        setReminders(taskId, kept + leads.distinct().sorted().map { TaskReminder(it) })
    }

    override fun updateSeries(seriesId: Long, occurrenceStart: Instant, form: TaskForm, dueReminders: List<Int>?) {
        val occurrence = task(seriesId, occurrenceStart)
        val anchors = resolver.query(
            taskUri(authority(), seriesId), arrayOf(Tasks.DTSTART, Tasks.DUE), null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val r = CursorColumnReader(c)
            r.getLong(Tasks.DTSTART) to r.getLong(Tasks.DUE)
        }
        fun shifted(anchor: Long?, was: Instant?, now: Instant?): Long? = when {
            now == null -> null
            anchor == null || was == null -> now.toEpochMilliseconds()
            else -> anchor + (now - was).inWholeMilliseconds
        }
        val values = TaskWriteMapper.seriesValues(form, ZoneId.systemDefault().id) +
            (Tasks.DTSTART to shifted(anchors?.first, occurrence?.start, form.start)) +
            (Tasks.DUE to shifted(anchors?.second, occurrence?.due, form.due))
        val rows = resolver.update(taskUri(authority(), seriesId), values.toContentValues(), null, null)
        if (rows == 0) throw TaskWriteFailedException("update series $seriesId")
        setCategories(seriesId, form.tags)
        writeDueReminders(seriesId, dueReminders)
    }

    /**
     * The provider has no series split of its own, so it is done by hand: the
     * master's rule is ended with an `UNTIL` and the rest continues as a new
     * task. Per-occurrence edits past the split point are dropped rather than
     * carried over — the provider offers no way to re-parent an exception.
     */
    override fun splitSeries(seriesId: Long, occurrenceStart: Instant, form: TaskForm, dueReminders: List<Int>?): Long {
        val spec = seriesSpec(seriesId) ?: throw TaskWriteFailedException("split series $seriesId")
        if (spec.rrule == null || occurrenceStart <= spec.anchor) {
            updateSeries(seriesId, occurrenceStart, form, dueReminders)
            return seriesId
        }
        // COUNT counts before EXDATE is applied (RFC 5545 §3.8.5.3).
        val spent = RecurrenceExpander.expand(
            spec.copy(exdate = null),
            ExpansionWindow(from = spec.anchor, until = occurrenceStart, maxOccurrences = SPLIT_COUNT_CEILING),
        ).size
        endSeriesBefore(seriesId, spec, occurrenceStart)
        val rule = form.rrule?.let { if (it == spec.rrule) TaskFormWriter.remainingCount(it, spent) else it }
        val reminders = dueReminders?.distinct()?.sorted()?.map { TaskReminder(it) }
        return insertTask(form.copy(rrule = rule, percentComplete = null), reminders)
    }

    override fun deleteFollowing(seriesId: Long, occurrenceStart: Instant) {
        val spec = seriesSpec(seriesId) ?: return
        if (spec.rrule == null || occurrenceStart <= spec.anchor) return deleteTask(seriesId)
        endSeriesBefore(seriesId, spec, occurrenceStart)
    }

    /** The series' rule set as stored on its master row, or null for a missing row. */
    private fun seriesSpec(seriesId: Long): RecurrenceSpec? = resolver.query(
        taskUri(authority(), seriesId),
        arrayOf(Tasks.RRULE, Tasks.RDATE, Tasks.EXDATE, Tasks.DTSTART, Tasks.DUE, Tasks.IS_ALLDAY, Tasks.TZ),
        null, null, null,
    )?.use { c ->
        if (!c.moveToFirst()) return@use null
        val r = CursorColumnReader(c)
        val anchor = r.getLong(Tasks.DTSTART) ?: r.getLong(Tasks.DUE) ?: return@use null
        RecurrenceSpec(
            rrule = r.getString(Tasks.RRULE)?.ifBlank { null },
            rdate = r.getString(Tasks.RDATE),
            exdate = r.getString(Tasks.EXDATE),
            anchor = Instant.fromEpochMilliseconds(anchor),
            isAllDay = r.getBoolean(Tasks.IS_ALLDAY),
            timeZone = r.getString(Tasks.TZ),
        )
    }

    /** End the master's rule before [occurrenceStart], and drop the exceptions past it. */
    private fun endSeriesBefore(seriesId: Long, spec: RecurrenceSpec, occurrenceStart: Instant) {
        val rule = TaskFormWriter.ruleEndedBefore(
            spec.rrule ?: return,
            occurrenceStart,
            spec.isAllDay,
            spec.timeZone,
            ZoneId.systemDefault().id,
        )
        val values = mapOf<String, Any?>(Tasks.RRULE to rule)
        val rows = resolver.update(taskUri(authority(), seriesId), values.toContentValues(), null, null)
        if (rows == 0) throw TaskWriteFailedException("end series $seriesId")
        exceptionsFrom(seriesId, occurrenceStart).forEach(::deleteTask)
    }

    /** Override rows of [seriesId] standing for [from] or a later occurrence. */
    private fun exceptionsFrom(seriesId: Long, from: Instant): List<Long> = resolver.query(
        TasksContract.tasksUri(authority()),
        arrayOf(Tasks.ID, Tasks.ORIGINAL_INSTANCE_TIME),
        "${Tasks.ORIGINAL_INSTANCE_ID} = ?",
        arrayOf(seriesId.toString()),
        null,
    )?.use { c ->
        val r = CursorColumnReader(c)
        buildList {
            while (c.moveToNext()) {
                val original = r.getLong(Tasks.ORIGINAL_INSTANCE_TIME) ?: continue
                if (original >= from.toEpochMilliseconds()) r.getLong(Tasks.ID)?.let(::add)
            }
        }
    } ?: emptyList()

    /** Through the instances URI, which the provider turns into an exception on the series. */
    override fun deleteOccurrence(seriesId: Long, occurrenceStart: Instant) {
        val instanceId = instanceIdFor(seriesId, occurrenceStart) ?: return
        resolver.delete(TasksContract.instanceUri(authority(), instanceId), null, null)
    }

    override fun updateInstance(taskId: Long, occurrenceStart: Instant, form: TaskForm, dueReminders: List<Int>?) {
        if (dueReminders == null) return writeInstance(taskId, occurrenceStart, form)
        // ⚠️ Written to the series, and then put back. Forking an occurrence
        // copies the task's *current* properties onto the new override row, so
        // setting the alarm beforehand is the only way to carry it across — and
        // leaving it there would silently give every other occurrence in the
        // series the reminder the user set on one of them.
        val seriesReminders = alarmRows(taskId).map { it.second }
        writeDueReminders(taskId, dueReminders)
        try {
            writeInstance(taskId, occurrenceStart, form)
        } finally {
            setReminders(taskId, seriesReminders)
        }
    }

    private fun writeInstance(taskId: Long, occurrenceStart: Instant, form: TaskForm) {
        val instanceId = instanceIdFor(taskId, occurrenceStart)
            ?: throw TaskWriteFailedException("update instance $taskId@$occurrenceStart: no such occurrence")
        val values = TaskWriteMapper.instanceValues(form, ZoneId.systemDefault().id)
        val uri = TasksContract.instanceUri(authority(), instanceId)
        // The fork copies the series' properties onto the override, so the tags
        // go onto the series first and are put back once the override exists.
        // Unchanged tags are inherited as they are, and the series is left alone.
        val seriesTags = categories(listOf(taskId))[taskId].orEmpty()
        val retag = normalizeTags(form.tags) != seriesTags
        if (retag) setCategories(taskId, form.tags)
        var failure: Throwable? = null
        try {
            val rows = resolver.update(uri, values.toContentValues(), null, null)
            if (rows == 0) throw TaskWriteFailedException("update instance $instanceId")
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            if (retag) {
                try {
                    setCategories(taskId, seriesTags)
                } catch (e: Throwable) {
                    failure?.addSuppressed(e) ?: throw e
                }
            }
        }
    }

    /**
     * The provider's instance row id for one occurrence.
     *
     * The seam addresses occurrences by `(taskId, occurrenceStart)`; writing
     * through the instances URI still needs the row id, so it is looked up here
     * rather than carried around above the data layer. Selection is on `task_id`
     * only — the anchor is matched in Kotlin because the column that holds it
     * (`instance_original_time`) is missing on older provider schemas, where a
     * WHERE clause naming it would throw instead of falling back.
     */
    private fun instanceIdFor(taskId: Long, occurrenceStart: Instant): Long? {
        val uri = TasksContract.instancesUri(authority())
        val selection = "${Instances.TASK_ID} = ?"
        return resolver.query(uri, null, selection, arrayOf(taskId.toString()), null)?.use { c ->
            val reader = CursorColumnReader(c)
            while (c.moveToNext()) {
                if (TaskMapper.occurrenceAnchor(reader) == occurrenceStart) {
                    return@use reader.getLong(Tasks.ID)
                }
            }
            null
        }
    }

    override fun setAlarm(taskId: Long, minutesBeforeDue: Int?) {
        val uri = TasksContract.propertiesUri(authority())
        // Replace rather than update: the provider's AlarmHandler re-validates the
        // whole row on every update, so a partial edit throws. Only the editable
        // (last before-due) row goes — a start-relative or additional alarm
        // another client wrote is not ours to drop.
        alarmRows(taskId).lastOrNull { !it.second.fromStart }?.let { (propertyId, _) ->
            resolver.delete(uri, "${Properties.PROPERTY_ID} = ?", arrayOf(propertyId.toString()))
        }
        if (minutesBeforeDue != null) {
            resolver.insert(uri, TaskWriteMapper.alarmValues(taskId, minutesBeforeDue).toContentValues())
                ?: throw TaskWriteFailedException("set alarm for task $taskId")
        }
    }

    override fun setReminders(taskId: Long, reminders: List<TaskReminder>) {
        val uri = TasksContract.propertiesUri(authority())
        resolver.delete(
            uri,
            "${Properties.TASK_ID} = ? AND ${Properties.MIMETYPE} = ?",
            arrayOf(taskId.toString(), TasksContract.Alarm.MIMETYPE),
        )
        reminders.forEach {
            resolver.insert(uri, TaskWriteMapper.alarmValues(taskId, it.minutesBefore, it.fromStart).toContentValues())
                ?: throw TaskWriteFailedException("set alarms for task $taskId")
        }
    }

    override fun reminders(): Map<Long, List<TaskReminder>> =
        readAlarms(null).groupBy({ it.first }) { it.third }

    override fun alarms(): Map<Long, TaskReminder> =
        reminders().mapNotNull { (id, list) -> list.editable()?.let { id to it } }.toMap()

    /** `(property_id, reminder)` for [taskId]'s alarm rows, in stored order. */
    private fun alarmRows(taskId: Long): List<Pair<Long, TaskReminder>> =
        readAlarms(taskId).map { it.second to it.third }

    /** `(task_id, property_id, reminder)` rows, for one task or (with `null`) all. */
    private fun readAlarms(taskId: Long?): List<Triple<Long, Long, TaskReminder>> {
        val uri = TasksContract.propertiesUri(authority())
        val projection = arrayOf(
            Properties.PROPERTY_ID,
            Properties.TASK_ID,
            TasksContract.Alarm.MINUTES_BEFORE,
            TasksContract.Alarm.REFERENCE,
        )
        val selection = buildString {
            append("${Properties.MIMETYPE} = ?")
            if (taskId != null) append(" AND ${Properties.TASK_ID} = ?")
        }
        val args = listOfNotNull(TasksContract.Alarm.MIMETYPE, taskId?.toString()).toTypedArray()
        return resolver.query(uri, projection, selection, args, Properties.PROPERTY_ID)?.use { c ->
            val reader = CursorColumnReader(c)
            buildList {
                while (c.moveToNext()) {
                    val propertyId = reader.getLong(Properties.PROPERTY_ID)
                    val id = reader.getLong(Properties.TASK_ID)
                    val minutes = reader.getInt(TasksContract.Alarm.MINUTES_BEFORE)
                    val reference = reader.getInt(TasksContract.Alarm.REFERENCE)
                    if (propertyId != null && id != null && minutes != null) {
                        add(
                            Triple(
                                id,
                                propertyId,
                                TaskReminder(
                                    minutesBefore = minutes,
                                    fromStart = reference == TasksContract.Alarm.REFERENCE_START,
                                ),
                            ),
                        )
                    }
                }
            }
        } ?: emptyList()
    }

    /** Tags by task id for [taskIds], in stored order. */
    private fun categories(taskIds: Collection<Long>): Map<Long, List<String>> {
        val rows = taskIds.distinct().chunked(MAX_SQL_ARGS - 1).flatMap { chunk ->
            val selection = "${Properties.MIMETYPE} = ? AND ${Properties.TASK_ID} IN (${chunk.joinToString(",") { "?" }})"
            val args = (listOf(TasksContract.Category.MIMETYPE) + chunk.map(Long::toString)).toTypedArray()
            resolver.query(
                TasksContract.propertiesUri(authority()),
                arrayOf(Properties.TASK_ID, TasksContract.Category.NAME),
                selection,
                args,
                Properties.PROPERTY_ID,
            )?.use { c ->
                val reader = CursorColumnReader(c)
                buildList {
                    while (c.moveToNext()) {
                        val id = reader.getLong(Properties.TASK_ID) ?: continue
                        val name = reader.getString(TasksContract.Category.NAME) ?: continue
                        add(id to name)
                    }
                }
            } ?: emptyList()
        }
        return rows.groupBy({ it.first }, { it.second }).mapValues { normalizeTags(it.value) }
    }

    /**
     * Replaces [taskId]'s category rows in one batch, so a refused insert cannot
     * leave half the tags, and leaves them alone when nothing changed so the task
     * isn't dirtied.
     */
    private fun setCategories(taskId: Long, tags: List<String>) {
        val wanted = normalizeTags(tags)
        if (categories(listOf(taskId))[taskId].orEmpty() == wanted) return
        val authority = authority()
        val uri = TasksContract.propertiesUri(authority)
        val ops = arrayListOf(
            ContentProviderOperation.newDelete(uri)
                .withSelection(
                    "${Properties.TASK_ID} = ? AND ${Properties.MIMETYPE} = ?",
                    arrayOf(taskId.toString(), TasksContract.Category.MIMETYPE),
                )
                .build(),
        )
        wanted.forEach {
            ops += ContentProviderOperation.newInsert(uri)
                .withValues(TaskWriteMapper.categoryValues(taskId, it).toContentValues())
                .build()
        }
        runCatching { resolver.applyBatch(authority, ops) }
            .getOrElse { throw TaskWriteFailedException("set categories for task $taskId", it) }
    }

    override fun setCancelled(taskId: Long, cancelled: Boolean) {
        val rows = resolver.update(taskUri(authority(), taskId), cancelValues(cancelled).toContentValues(), null, null)
        if (rows == 0) throw TaskWriteFailedException("cancel task $taskId")
    }

    /** Through the instances URI, like [setCompletedInstance]. */
    override fun setCancelledInstance(taskId: Long, occurrenceStart: Instant, cancelled: Boolean) {
        val instanceId = instanceIdFor(taskId, occurrenceStart)
            ?: return setCancelled(taskId, cancelled)
        val uri = TasksContract.instanceUri(authority(), instanceId)
        val rows = resolver.update(uri, cancelValues(cancelled).toContentValues(), null, null)
        if (rows == 0) throw TaskWriteFailedException("cancel instance $instanceId")
    }

    private fun cancelValues(cancelled: Boolean) = mapOf(
        Tasks.STATUS to if (cancelled) TasksContract.STATUS_CANCELLED else TasksContract.STATUS_NEEDS_ACTION,
        Tasks.COMPLETED to null,
    )

    override fun setCompleted(taskId: Long, completed: Boolean) {
        val values = TaskWriteMapper.completionValues(completed, System.currentTimeMillis())
        val rows = resolver.update(taskUri(authority(), taskId), values.toContentValues(), null, null)
        if (rows == 0) throw TaskWriteFailedException("complete task $taskId")
    }

    /**
     * Through the instances URI, which is what makes the provider fork an override
     * rather than close the series. No instance row for the anchor means the task
     * is not a series after all — the plain write is then the right one.
     */
    override fun setCompletedInstance(taskId: Long, occurrenceStart: Instant, completed: Boolean) {
        val instanceId = instanceIdFor(taskId, occurrenceStart)
            ?: return setCompleted(taskId, completed)
        val values = TaskWriteMapper.completionValues(completed, System.currentTimeMillis())
        val uri = TasksContract.instanceUri(authority(), instanceId)
        val rows = resolver.update(uri, values.toContentValues(), null, null)
        if (rows == 0) throw TaskWriteFailedException("complete instance $instanceId")
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

    override fun updateList(listId: Long, name: String, color: Int) {
        val values = TaskWriteMapper.listValues(name, color)
        val rows = resolver.update(listSyncUri(listId), values.toContentValues(), null, null)
        if (rows == 0) throw TaskWriteFailedException("update list $listId")
    }

    override fun deleteList(listId: Long) {
        val rows = resolver.delete(listSyncUri(listId), null, null)
        if (rows == 0) throw TaskWriteFailedException("delete list $listId")
    }

    /**
     * A list row addressed as its own account's sync adapter — the provider only
     * lets that caller write the `tasklists` table, and the account has to be the
     * row's own (the params are matched against it, not merely accepted).
     */
    private fun listSyncUri(listId: Long): Uri {
        val authority = authority()
        val uri = TasksContract.listUri(authority, listId)
        val account = resolver.query(uri, arrayOf(Lists.ACCOUNT_NAME, Lists.ACCOUNT_TYPE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() to c.getString(1).orEmpty() else null }
            ?: throw TaskWriteFailedException("list $listId not found")
        return TasksContract.asSyncAdapter(uri, account.first, account.second)
    }

    // --- observation ----------------------------------------------------------

    override fun registerObserver(onChange: () -> Unit): AutoCloseable {
        val provider = providerResolver.resolve() ?: return AutoCloseable { }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        // Register both or neither: if the second call throws, the first
        // registration would otherwise leak (no AutoCloseable was handed back yet).
        try {
            resolver.registerContentObserver(TasksContract.instancesUri(provider.authority), true, observer)
            resolver.registerContentObserver(TasksContract.listsUri(provider.authority), true, observer)
        } catch (e: RuntimeException) {
            runCatching { resolver.unregisterContentObserver(observer) }
            throw e
        }
        return AutoCloseable { runCatching { resolver.unregisterContentObserver(observer) } }
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

/** SQLite's default bound-argument limit on older Android releases. */
private const val MAX_SQL_ARGS = 999

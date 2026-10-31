package com.aiexile.agendula.data.tasks.provider

import android.database.sqlite.SQLiteConstraintException
import com.aiexile.agendula.data.di.ApplicationScope
import com.aiexile.agendula.data.reminders.ReminderScheduler
import com.aiexile.agendula.data.tasks.ProviderResolver
import com.aiexile.agendula.data.tasks.StorageMode
import com.aiexile.agendula.data.tasks.TaskWriteFailedException
import com.aiexile.agendula.data.tasks.TasksContract.Alarm
import com.aiexile.agendula.data.tasks.TasksContract.Category
import com.aiexile.agendula.data.tasks.TasksContract.Instances
import com.aiexile.agendula.data.tasks.TasksContract.Properties
import com.aiexile.agendula.data.tasks.TasksContract.Tasks
import com.aiexile.agendula.data.tasks.room.AlarmReference
import com.aiexile.agendula.data.tasks.room.RoomTasksDataSource
import com.aiexile.agendula.data.tasks.room.TaskAlarmEntity
import com.aiexile.agendula.data.tasks.room.TaskEntity
import com.aiexile.agendula.data.tasks.room.TasksDatabase
import com.aiexile.agendula.data.tasks.room.seriesOccurrences
import com.aiexile.agendula.domain.TaskStatus
import com.aiexile.agendula.domain.normalizeTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/** One row by id, `<id column> = N` or `<id column> = ?`. */
private val SINGLE_ROW = Regex("""\s*(\w+)\s*=\s*(\d+|\?)\s*""")

/**
 * Applies another app's writes to the own store for [AgendulaTaskProvider].
 *
 * A write's selection runs against the published snapshot, so it matches the
 * rows the client can read, and each matched row is then written through
 * [RoomTasksDataSource] under the rules the app's own edits follow. `tasklists`
 * stays read-only. On `instances`, completion columns tick off that one
 * occurrence of a series; other content columns edit the series, and a changed
 * time column is refused, since one occurrence cannot be moved this way.
 *
 * Each call runs in one transaction, and a batch in one more around them.
 */
@Singleton
class ProviderWriter internal constructor(
    private val database: TasksDatabase,
    private val store: RoomTasksDataSource,
    private val source: ProviderSnapshotSource,
    private val resolver: ProviderResolver,
    private val afterWrite: () -> Unit,
    private val clock: Clock = Clock.System,
) {

    @Inject constructor(
        database: TasksDatabase,
        store: RoomTasksDataSource,
        source: ProviderSnapshotSource,
        resolver: ProviderResolver,
        scheduler: ReminderScheduler,
        @ApplicationScope scope: CoroutineScope,
    ) : this(database, store, source, resolver, { scope.launch { runCatching { scheduler.sync() } } })

    private var batchDepth = 0
    private var batchWrote = false

    /** Inserts one row and returns its published id. */
    fun insert(table: ProviderTable, values: ProviderValues): Long = write {
        when (table) {
            ProviderTable.TASKS -> insertTask(values)
            ProviderTable.PROPERTIES -> insertProperty(values)
            else -> throw UnsupportedOperationException("${table.path} is read-only")
        }
    }

    /** Updates every row [selection] matches and returns how many changed. */
    fun update(table: ProviderTable, values: ProviderValues, selection: String?, args: Array<out String>?): Int = write {
        when (table) {
            ProviderTable.TASKS -> {
                val writable = ProviderTaskWriter.writable(table, values, WRITABLE_TASK_COLUMNS)
                val matched = ids(table, selection, args)
                (if (Tasks.LIST_ID in writable) parentsFirst(matched) else matched).count { updateTask(it, writable) }
            }
            ProviderTable.INSTANCES -> {
                val writable = ProviderTaskWriter.writable(table, values, WRITABLE_TASK_COLUMNS)
                instances(selection, args).count { updateInstance(it, writable) }
            }
            ProviderTable.PROPERTIES -> ids(table, selection, args).count { updateProperty(it, values) }
            else -> throw UnsupportedOperationException("${table.path} is read-only")
        }
    }

    /** Deletes every row [selection] matches and returns how many went. */
    fun delete(table: ProviderTable, selection: String?, args: Array<out String>?): Int = write {
        when (table) {
            ProviderTable.TASKS -> ids(table, selection, args).count { deleteTask(it) }
            ProviderTable.INSTANCES -> instances(selection, args).count { deleteInstance(it) }
            ProviderTable.PROPERTIES -> ids(table, selection, args).count { deleteProperty(it) }
            else -> throw UnsupportedOperationException("${table.path} is read-only")
        }
    }

    /** Runs a client's batch in one transaction, re-arming reminders once at the end. */
    fun <T> batch(block: () -> T): T = source.locked {
        batchDepth++
        try {
            database.runInTransaction<T> { block() }
        } finally {
            // A snapshot built mid-batch may hold rows a failure rolled back.
            source.invalidate()
            batchDepth--
            if (batchDepth == 0 && batchWrote) {
                batchWrote = false
                afterWrite()
            }
        }
    }

    private fun <T> write(block: () -> T): T = source.locked {
        check(resolver.mode() == StorageMode.OWN) {
            "Agendula is working on top of an external tasks provider; write to that one instead"
        }
        val result = try {
            database.runInTransaction<T> { block() }
        } catch (e: TaskWriteFailedException) {
            throw IllegalArgumentException(e.message, e)
        } catch (e: SQLiteConstraintException) {
            throw IllegalArgumentException(e.message, e)
        } finally {
            source.invalidate()
        }
        if (batchDepth == 0) afterWrite() else batchWrote = true
        result
    }

    // --- tasks ------------------------------------------------------------------

    private fun insertTask(values: ProviderValues): Long {
        require(values[Tasks.ORIGINAL_INSTANCE_ID] == null && values[Tasks.ORIGINAL_INSTANCE_TIME] == null) {
            "Modify an occurrence through ${Instances.PATH}, not by inserting one"
        }
        val writable = ProviderTaskWriter.writable(ProviderTable.TASKS, values, WRITABLE_TASK_COLUMNS)
        val task = ProviderTaskWriter.newTask(writable, UUID.randomUUID().toString(), clock.now(), zone())
        requireWritableList(task.listId)
        // The unique index cannot catch this: SQLite treats the masters' NULL recurrence_id as distinct.
        require(database.tasks().byUid(task.listId, task.uid) == null) { "List ${task.listId} already has a task ${task.uid}" }
        requireParent(task)
        return store.insertEntity(task)
    }

    private fun updateTask(taskId: Long, values: ProviderValues): Boolean {
        val current = database.tasks().entity(taskId)?.takeUnless { it.isDeleted } ?: return false
        requireWritableList(current.listId)
        val edited = ProviderTaskWriter.patch(current, values, clock.now(), zone())
        if (edited == current) return true
        val moved = edited.listId != current.listId
        if (moved) {
            requireWritableList(edited.listId)
            requireFreeUid(edited.listId, current.uid)
        }
        if (edited.parentId != current.parentId || moved) requireParent(edited)
        store.replaceEntity(current, edited)
        // A subtree moves as one: a subtask has to be in its parent's list.
        if (moved) store.subtaskIds(taskId).forEach { moveSubtree(it, edited.listId, hashSetOf(taskId)) }
        return true
    }

    private fun moveSubtree(taskId: Long, listId: Long, seen: MutableSet<Long> = HashSet()) {
        if (!seen.add(taskId)) return
        val current = database.tasks().entity(taskId)?.takeUnless { it.isDeleted } ?: return
        if (current.listId != listId) {
            requireFreeUid(listId, current.uid)
            store.replaceEntity(current, current.copy(listId = listId, lastModified = clock.now(), isDirty = true))
        }
        store.subtaskIds(taskId).forEach { moveSubtree(it, listId, seen) }
    }

    /**
     * [ids] with every parent before its subtasks, so a bulk move carries a
     * subtree along before reaching its subtasks, whatever order they came in.
     */
    private fun parentsFirst(ids: List<Long>): List<Long> {
        if (ids.size < 2) return ids
        fun depth(id: Long): Int {
            val seen = HashSet<Long>()
            var parent = database.tasks().entity(id)?.parentId
            while (parent != null && seen.add(parent)) parent = database.tasks().entity(parent)?.parentId
            return seen.size
        }
        val depths = ids.associateWith(::depth)
        return ids.sortedBy(depths::getValue)
    }

    private fun deleteTask(taskId: Long): Boolean {
        val current = database.tasks().entity(taskId)?.takeUnless { it.isDeleted } ?: return false
        requireWritableList(current.listId)
        // An override deletes itself into an EXDATE on its master.
        if (current.masterId != null) store.deleteTask(taskId) else store.deleteWithSubtasks(taskId)
        return true
    }

    // --- instances --------------------------------------------------------------

    /** One occurrence: the row it reads from, its series, and when it was due to happen. */
    private class InstanceRow(val taskId: Long, val seriesId: Long, val originalTime: Instant?, val recurring: Boolean)

    private fun instances(selection: String?, args: Array<out String>?): List<InstanceRow> {
        singleId(ProviderTable.INSTANCES, selection, args)?.let { return listOfNotNull(instanceRow(it)) }
        return source.rows(
            ProviderTable.INSTANCES,
            listOf(Tasks.ID, Instances.TASK_ID, Instances.INSTANCE_ORIGINAL_TIME, Instances.IS_RECURRING),
            selection,
            args,
        ).map { (id, taskId, original, recurring) ->
            InstanceRow(
                taskId = taskId as Long,
                seriesId = ProviderIds.instanceSeries(id as Long),
                originalTime = (original as Long?)?.let(Instant::fromEpochMilliseconds),
                recurring = recurring == 1L,
            )
        }
    }

    /** [instanceId] read straight from the store, without rebuilding the snapshot. */
    private fun instanceRow(instanceId: Long): InstanceRow? {
        val master = database.tasks().entity(ProviderIds.instanceSeries(instanceId))
            ?.takeIf { !it.isDeleted && it.masterId == null } ?: return null
        val occurrences = seriesOccurrences(master, database.tasks().overrides(master.id), clock.now())
            ?: return InstanceRow(master.id, master.id, null, recurring = false)
                .takeIf { ProviderIds.instanceSeconds(instanceId) == 0L }
        val occurrence = occurrences.firstOrNull { ProviderIds.instance(master.id, it.anchor) == instanceId } ?: return null
        return InstanceRow(occurrence.override?.id ?: master.id, master.id, occurrence.anchor, recurring = true)
    }

    private fun updateInstance(row: InstanceRow, values: ProviderValues): Boolean {
        val occurrence = row.originalTime
        if (!row.recurring || occurrence == null) return updateTask(row.taskId, values)
        val shown = database.tasks().entity(row.taskId)?.takeUnless { it.isDeleted } ?: return false
        requireWritableList(shown.listId)
        // A row read back carries every column; only what differs from it is a write.
        val changed = ProviderTaskWriter.changedColumns(shown, values, zone())
        val moved = changed intersect TIME_COLUMNS
        if (moved.isNotEmpty()) {
            // A bad value reports itself rather than as a move.
            ProviderTaskWriter.patch(shown, values.filterKeys { it in moved }, clock.now(), zone())
            throw IllegalArgumentException(
                "One occurrence of a recurring task cannot be moved (${moved.sorted().joinToString()}); write the series through ${Tasks.PATH}",
            )
        }
        val content = values.filterKeys { it in changed && it !in COMPLETION_COLUMNS }
        if (content.isNotEmpty()) {
            updateTask(row.seriesId, content)
            // A modified occurrence shows its own copy of the content, so it takes the write too.
            if (row.taskId != row.seriesId) updateTask(row.taskId, content - Tasks.LIST_ID - Tasks.PARENT_ID)
        }
        if (changed.none { it in COMPLETION_COLUMNS }) return true
        // The column the client changed decides, not the unchanged ones it carries along.
        val change = ProviderTaskWriter.occurrenceChange(values.filterKeys { it in changed && it in COMPLETION_COLUMNS })
            ?: return true
        val status = database.tasks().entity(row.taskId)?.status
        when (change) {
            OccurrenceChange.COMPLETE ->
                if (status != TaskStatus.COMPLETED) store.setCompletedInstance(row.taskId, occurrence, true)
            OccurrenceChange.CANCEL ->
                if (status != TaskStatus.CANCELLED) store.setCancelledInstance(row.taskId, occurrence, true)
            OccurrenceChange.REOPEN ->
                if (status != TaskStatus.NEEDS_ACTION) store.setCompletedInstance(row.taskId, occurrence, false)
        }
        return true
    }

    private fun deleteInstance(row: InstanceRow): Boolean {
        val occurrence = row.originalTime
        if (!row.recurring || occurrence == null) return deleteTask(row.taskId)
        val task = database.tasks().entity(row.taskId)?.takeUnless { it.isDeleted } ?: return false
        requireWritableList(task.listId)
        if (task.masterId != null) store.deleteTask(task.id) else store.deleteOccurrence(task.id, occurrence)
        return true
    }

    // --- properties -------------------------------------------------------------

    /** What a published property id stands for, read from the store. */
    private sealed interface PropertyRef {
        val task: TaskEntity

        class AlarmRow(val alarm: TaskAlarmEntity, override val task: TaskEntity) : PropertyRef
        class CategoryRow(val name: String, override val task: TaskEntity) : PropertyRef
    }

    private fun propertyRef(propertyId: Long): PropertyRef? {
        if (ProviderIds.isAlarm(propertyId)) {
            val alarm = database.alarms().entity(ProviderIds.alarmRow(propertyId)) ?: return null
            val task = database.tasks().entity(alarm.taskId)?.takeUnless { it.isDeleted } ?: return null
            return PropertyRef.AlarmRow(alarm, task)
        }
        val task = database.tasks().entity(ProviderIds.categoryTask(propertyId))?.takeUnless { it.isDeleted } ?: return null
        val name = ProviderIds.categories(task.id, task.categories).entries.firstOrNull { it.value == propertyId }?.key
            ?: return null
        return PropertyRef.CategoryRow(name, task)
    }

    private fun insertProperty(values: ProviderValues): Long {
        val v = Values(ProviderTaskWriter.writable(ProviderTable.PROPERTIES, values, values.keys))
        val taskId = requireNotNull(v.long(Properties.TASK_ID)) { "A property needs a ${Properties.TASK_ID}" }
        val task = liveTask(taskId)
        return when (v.string(Properties.MIMETYPE)) {
            Alarm.MIMETYPE -> {
                val alarm = alarm(TaskAlarmEntity(taskId = taskId, minutesBefore = 0), v, requireMinutes = true)
                var id = 0L
                store.writeAlarm(taskId) { id = database.alarms().insert(alarm) }
                ProviderIds.alarm(id)
            }
            Category.MIMETYPE -> {
                val name = requireNotNull(v.text(Category.NAME)) { "A category needs a ${Category.NAME}" }
                setCategories(task, task.categories + name)
                val categories = database.tasks().entity(taskId)!!.categories
                ProviderIds.categories(taskId, categories).getValue(name)
            }
            else -> throw IllegalArgumentException("Only alarm and category properties can be written")
        }
    }

    private fun updateProperty(propertyId: Long, values: ProviderValues): Boolean {
        val v = Values(ProviderTaskWriter.writable(ProviderTable.PROPERTIES, values, values.keys - Properties.PROPERTY_ID))
        val ref = propertyRef(propertyId) ?: return false
        requireWritableList(ref.task.listId)
        requireSame(v, Properties.TASK_ID, ref.task.id)
        val mimetype = if (ref is PropertyRef.AlarmRow) Alarm.MIMETYPE else Category.MIMETYPE
        if (Properties.MIMETYPE in v) require(v.string(Properties.MIMETYPE) == mimetype) { "A property's mimetype cannot change" }
        when (ref) {
            is PropertyRef.AlarmRow -> {
                val edited = alarm(ref.alarm, v, requireMinutes = false)
                if (edited != ref.alarm) store.writeAlarm(ref.task.id) { database.alarms().update(edited) }
            }
            is PropertyRef.CategoryRow -> if (Category.NAME in v) {
                val name = requireNotNull(v.text(Category.NAME)) { "A category needs a ${Category.NAME}" }
                setCategories(ref.task, ref.task.categories.map { if (it == ref.name) name else it })
            }
        }
        return true
    }

    private fun deleteProperty(propertyId: Long): Boolean {
        val ref = propertyRef(propertyId) ?: return false
        requireWritableList(ref.task.listId)
        when (ref) {
            is PropertyRef.AlarmRow -> store.writeAlarm(ref.task.id) { database.alarms().delete(ref.alarm.id) }
            is PropertyRef.CategoryRow -> setCategories(ref.task, ref.task.categories - ref.name)
        }
        return true
    }

    private fun alarm(base: TaskAlarmEntity, v: Values, requireMinutes: Boolean): TaskAlarmEntity {
        if (requireMinutes) requireNotNull(v.int(Alarm.MINUTES_BEFORE)) { "An alarm needs ${Alarm.MINUTES_BEFORE}" }
        val reference = when (if (Alarm.REFERENCE in v) v.int(Alarm.REFERENCE) else null) {
            null -> base.reference
            Alarm.REFERENCE_DUE -> AlarmReference.DUE
            Alarm.REFERENCE_START -> AlarmReference.START
            else -> throw IllegalArgumentException("${Alarm.REFERENCE} must be ${Alarm.REFERENCE_DUE} (due) or ${Alarm.REFERENCE_START} (start)")
        }
        return base.copy(
            minutesBefore = if (Alarm.MINUTES_BEFORE in v) requireNotNull(v.int(Alarm.MINUTES_BEFORE)) {
                "${Alarm.MINUTES_BEFORE} cannot be null"
            } else base.minutesBefore,
            reference = reference,
            message = if (Alarm.MESSAGE in v) v.text(Alarm.MESSAGE) else base.message,
        )
    }

    private fun setCategories(task: TaskEntity, categories: List<String>) {
        val normalized = normalizeTags(categories)
        if (normalized == task.categories) return
        store.replaceEntity(task, task.copy(categories = normalized, lastModified = clock.now(), isDirty = true))
    }

    // --- checks -----------------------------------------------------------------

    /**
     * The ids [selection] matches. A row URI's plain `<id column> = N` is checked
     * against the store directly; anything else runs against the snapshot.
     */
    private fun ids(table: ProviderTable, selection: String?, args: Array<out String>?): List<Long> {
        singleId(table, selection, args)?.let { id ->
            val exists = when (table) {
                ProviderTable.TASKS -> database.tasks().entity(id)?.isDeleted == false
                ProviderTable.PROPERTIES -> propertyRef(id) != null
                else -> true
            }
            return listOfNotNull(id.takeIf { exists })
        }
        return source.rows(table, listOf(table.idColumn), selection, args).map { it.single() as Long }
    }

    private fun singleId(table: ProviderTable, selection: String?, args: Array<out String>?): Long? {
        val match = SINGLE_ROW.matchEntire(selection ?: return null) ?: return null
        if (match.groupValues[1] != table.idColumn) return null
        val value = match.groupValues[2]
        return when {
            value == "?" -> args?.singleOrNull()?.trim()?.toLongOrNull()
            args.isNullOrEmpty() -> value.toLongOrNull()
            else -> null
        }
    }

    private fun requireFreeUid(listId: Long, uid: String) {
        require(database.tasks().byUid(listId, uid)?.isDeleted != false) { "List $listId already has a task $uid" }
    }

    private fun liveTask(taskId: Long): TaskEntity {
        val task = requireNotNull(database.tasks().entity(taskId)?.takeUnless { it.isDeleted }) { "No task $taskId" }
        requireWritableList(task.listId)
        return task
    }

    private fun requireWritableList(listId: Long) {
        val list = requireNotNull(database.taskLists().entity(listId)) { "No task list $listId" }
        if (list.isReadOnly) throw UnsupportedOperationException("Task list $listId is read-only")
    }

    /** A parent has to be a live, top-level task in the same list, and not one of the task's own subtasks. */
    private fun requireParent(task: TaskEntity) {
        var parentId = task.parentId ?: return
        val parent = database.tasks().entity(parentId)?.takeUnless { it.isDeleted }
        require(parent != null && parent.masterId == null) { "No task $parentId to be a parent" }
        require(parent.listId == task.listId) { "A subtask has to be in its parent's list" }
        val seen = HashSet<Long>()
        while (true) {
            require(parentId != task.id) { "Task ${task.id} cannot be its own ancestor" }
            if (!seen.add(parentId)) break
            parentId = database.tasks().entity(parentId)?.parentId ?: break
        }
    }

    private fun requireSame(v: Values, column: String, expected: Long) {
        if (column in v) require(v.long(column) == expected) { "A property's $column cannot change" }
    }

    private fun zone(): String = ZoneId.systemDefault().id
}

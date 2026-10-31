package com.aiexile.agendula.data.tasks.room

import androidx.room.InvalidationTracker
import com.aiexile.agendula.data.tasks.TaskQuery
import com.aiexile.agendula.domain.TaskReminder
import com.aiexile.agendula.domain.TaskReminderOrder
import com.aiexile.agendula.data.tasks.TaskWriteFailedException
import com.aiexile.agendula.data.tasks.TasksDataSource
import com.aiexile.agendula.domain.Task
import com.aiexile.agendula.domain.TaskForm
import com.aiexile.agendula.domain.TaskList
import com.aiexile.agendula.domain.TaskStatus
import com.aiexile.agendula.domain.export.ExportTask
import com.aiexile.agendula.domain.recurrence.ExpansionWindow
import com.aiexile.agendula.domain.recurrence.RecurrenceExpander
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** How far either side of now a series is expanded. */
private val WINDOW_BACK = 365.days
private val WINDOW_FORWARD = 730.days

/** Residue lines that describe the old series and must not follow the new one. */
private val SERIES_LINES = listOf("EXDATE", "RDATE", "RRULE", "RECURRENCE-ID", "UID")

/** Enough to count every occurrence before a split point without bounding a real series. */
internal const val SPLIT_COUNT_CEILING = 100_000

/** The window every read of the store expands a series over. */
internal fun expansionWindow(now: Instant) = ExpansionWindow(
    from = now - WINDOW_BACK,
    until = now + WINDOW_FORWARD,
    pivot = now,
)

/**
 * One occurrence's resolved start and due. A timed series keeps each
 * occurrence's duration; a due-anchored one has no start to offset from, so
 * the anchor *is* the due date.
 */
internal fun occurrenceTimes(master: TaskEntity, anchor: Instant): Pair<Instant?, Instant?> {
    if (master.dtstart == null) return null to anchor
    val length = master.due?.let { it - master.dtstart }
    return anchor to length?.let { anchor + it }
}

/** One occurrence of a series: its anchor, the override replacing it if any, and its resolved times. */
internal class SeriesOccurrence(
    val anchor: Instant,
    val override: TaskEntity?,
    val start: Instant?,
    val due: Instant?,
    val distance: Int?,
)

/**
 * Every occurrence of [master] inside the expansion window, with any
 * `RECURRENCE-ID` override substituted for the occurrence it replaces, or
 * `null` when [master] does not recur.
 */
internal fun seriesOccurrences(master: TaskEntity, overrides: List<TaskEntity>, now: Instant): List<SeriesOccurrence>? {
    val spec = master.recurrenceSpec() ?: return null
    val anchors = RecurrenceExpander.expand(spec, expansionWindow(now))
    val distances = RecurrenceExpander.distancesFromCurrent(anchors, now)
    val byAnchor = overrides.associateBy { it.recurrenceId }
    return anchors.mapIndexed { index, anchor ->
        val override = byAnchor[anchor]
        val (start, due) = if (override != null) override.dtstart to override.due else occurrenceTimes(master, anchor)
        SeriesOccurrence(anchor, override, start, due, distances.getOrNull(index))
    }
}

internal val OBSERVED_TABLES = arrayOf("tasks", "task_lists", "task_alarms", "accounts")

/** Distinct alarm texts whose `ACKNOWLEDGED` is remembered between scheduler passes. */
private const val ACK_CACHE_SIZE = 512

/**
 * [TasksDataSource] over Agendula's own Room store.
 *
 * The one structural difference from [com.aiexile.agendula.data.tasks
 * .AndroidTasksDataSource]: there is no materialised instances table, so a
 * recurring series is expanded here, at read time, by [RecurrenceExpander].
 * Nothing above this cares — the repository already filters and sorts in Kotlin.
 */
@Singleton
class RoomTasksDataSource @Inject constructor(
    private val database: TasksDatabase,
    private val writes: LocalWriteListener,
) : TasksDataSource {

    /** Without a listener, for tests that exercise the store alone. */
    constructor(database: TasksDatabase) : this(database, LocalWriteListener.NONE)

    private val clock: Clock = Clock.System

    private val tasks get() = database.tasks()
    private val lists get() = database.taskLists()
    private val alarms get() = database.alarms()

    // --- reads ----------------------------------------------------------------

    override fun taskLists(): List<TaskList> = lists.lists().map(RoomTaskMapper::taskList)

    override fun tasks(query: TaskQuery): List<Task> {
        val now = clock.now()
        val overrides = tasks.allOverrides(query.listId).groupBy { it.masterId }
        return tasks.tasks(query.listId, query.includeCompleted)
            .flatMap { occurrencesOf(it, overrides[it.task.id].orEmpty(), now) }
            .filter { query.includeCompleted || !it.isClosed }
    }

    override fun task(taskId: Long): Task? {
        val row = tasks.task(taskId) ?: return null
        // An override row is one occurrence in its own right; it names the
        // occurrence it replaces rather than expanding to a series.
        row.task.recurrenceId?.let { return overrideTask(row, it) }
        val now = clock.now()
        val occurrences = occurrencesOf(row, tasks.overrides(taskId), now)
        return occurrences.firstOrNull { it.distanceFromCurrent == 0 } ?: occurrences.firstOrNull()
    }

    override fun task(taskId: Long, occurrenceStart: Instant?): Task? {
        if (occurrenceStart == null) return task(taskId)
        val row = tasks.task(taskId) ?: return null
        if (row.task.recurrenceId != null || !row.task.isRecurring) return task(taskId)
        return occurrencesOf(row, tasks.overrides(taskId), clock.now())
            .firstOrNull { it.occurrenceStart == occurrenceStart }
            ?: task(taskId)
    }

    private fun overrideTask(row: TaskRow, anchor: Instant): Task =
        RoomTaskMapper.task(
            row,
            occurrenceStart = anchor,
            seriesId = row.task.masterId,
            recurrenceRule = row.task.masterId?.let { tasks.entity(it)?.rrule },
        )

    override fun subtasks(parentTaskId: Long): List<Task> {
        val now = clock.now()
        return tasks.subtasks(parentTaskId)
            .flatMap { occurrencesOf(it, tasks.overrides(it.task.id), now) }
    }

    /** One transaction, so a failure part-way leaves the subtree whole. */
    override fun deleteWithSubtasks(taskId: Long) = tx { super.deleteWithSubtasks(taskId) }

    override fun subtaskIds(parentTaskId: Long): List<Long> =
        tasks.subtasks(parentTaskId).map { it.task.id }.filter { it != parentTaskId }

    override fun exportTasks(listId: Long): List<ExportTask> =
        tasks.exportTasks(listId).map(RoomTaskMapper::exportTask)

    override fun reminders(): Map<Long, List<TaskReminder>> {
        val stored = alarms.all().groupBy({ it.taskId }) { it.asReminder().copy(acknowledgedAt = acknowledged(it)) }
        // Turned off elsewhere: present and empty, so nothing falls back to a default.
        return stored + alarms.remindersOff().filterNot(stored::containsKey).associateWith { emptyList() }
    }

    /** `raw` → its `ACKNOWLEDGED`, so a scheduler pass does not re-parse every alarm. */
    private val acknowledgements = object : LinkedHashMap<String, Instant?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Instant?>) = size > ACK_CACHE_SIZE
    }

    private fun acknowledged(row: TaskAlarmEntity): Instant? {
        val raw = row.raw ?: return null
        return synchronized(acknowledgements) {
            if (raw in acknowledgements) acknowledgements[raw] else row.acknowledgedAt().also { acknowledgements[raw] = it }
        }
    }

    /**
     * Every occurrence of [row] inside the expansion window, with any
     * `RECURRENCE-ID` override substituted for the occurrence it replaces.
     *
     * A non-recurring task is its own single occurrence and carries a null
     * [Task.occurrenceStart], so it keys and edits by task id exactly as before.
     */
    private fun occurrencesOf(row: TaskRow, overrides: List<TaskEntity>, now: Instant): List<Task> {
        val occurrences = seriesOccurrences(row.task, overrides, now) ?: return listOf(RoomTaskMapper.task(row))
        return occurrences.map { occurrence ->
            val override = occurrence.override
            if (override != null) {
                RoomTaskMapper.task(
                    row = row.copy(task = override),
                    occurrenceStart = occurrence.anchor,
                    start = occurrence.start,
                    due = occurrence.due,
                    distanceFromCurrent = occurrence.distance,
                    seriesId = row.task.id,
                    recurrenceRule = row.task.rrule,
                )
            } else {
                RoomTaskMapper.task(
                    row = row,
                    occurrenceStart = occurrence.anchor,
                    start = occurrence.start,
                    due = occurrence.due,
                    distanceFromCurrent = occurrence.distance,
                )
            }
        }
    }

    // --- writes ---------------------------------------------------------------

    override fun insertTask(form: TaskForm, reminders: List<TaskReminder>?): Long {
        if (lists.exists(form.listId) == 0) throw TaskWriteFailedException("insert task: no list ${form.listId}")
        val entity = TaskFormWriter.newTask(form, uid = UUID.randomUUID().toString(), now = clock.now(), tzId = zone())
        var id = 0L
        tx {
            id = tasks.insert(TaskFormWriter.withRule(entity, form.rrule))
            reminders?.let { alarms.syncForTask(id, it) }
            writes.onWritten(setOf(form.listId))
        }
        return id
    }

    /**
     * [reminders] as [taskId]'s own, inside the caller's transaction.
     *
     * ⚠️ Part of the task write rather than a second call after it. Once
     * reminders sync, a sync landing between the two would upload the task
     * without them, or with the ones it is about to lose.
     */
    private fun writeReminders(taskId: Long, reminders: List<TaskReminder>?) {
        if (reminders == null) return
        alarms.syncForTask(taskId, reminders)
    }

    private fun storedReminders(taskId: Long): List<TaskReminder> =
        alarms.forTask(taskId).map { it.asReminder() }.sortedWith(TaskReminderOrder)

    override fun updateTask(taskId: Long, form: TaskForm, reminders: List<TaskReminder>?) = tx {
        val current = tasks.entity(taskId) ?: throw TaskWriteFailedException("update task $taskId")
        val edited = TaskFormWriter.apply(current, form, clock.now(), zone())
        writeReminders(taskId, reminders)
        if (current.masterId == null && edited.listId != current.listId) {
            if (lists.exists(edited.listId) == 0) throw TaskWriteFailedException("move task: no list ${edited.listId}")
            move(current, TaskFormWriter.withRule(edited, form.rrule))
            writes.onWritten(setOf(current.listId, edited.listId))
            return@tx
        }
        // ⚠️ An override's list and parent are the master's, the rule
        // [updateInstance] states and this path has to keep. An overridden
        // occurrence maps with `isRecurring = false`, so the repository routes
        // it here and the edit screen offers its list picker — and a row that
        // took `list_id = B` while its `master_id` stayed in list A is invisible
        // in both (the task query skips non-null `master_id`, and the override
        // query finds no master in B) while still uploading as part of A's
        // resource.
        tasks.update(
            if (current.masterId == null) {
                TaskFormWriter.withRule(edited, form.rrule)
            } else {
                edited.copy(listId = current.listId, parentId = current.parentId)
            },
        )
        writes.onWritten(setOf(current.listId))
    }

    /** See [TaskMove]: the rows move, and the old collection is left a tombstone. */
    private fun move(current: TaskEntity, edited: TaskEntity) {
        val plan = TaskMove.plan(
            edited = edited,
            previous = current,
            overrides = tasks.allOverridesOf(current.id),
            sourceSynced = lists.entity(current.listId)?.accountId != null,
            targetTombstone = tasks.byUid(edited.listId, current.uid),
        )
        if (plan.delete.isNotEmpty()) tasks.deleteAll(plan.delete)
        plan.update.forEach { tasks.update(it) }
        plan.tombstone?.let { tasks.insert(it) }
    }

    override fun updateSeries(seriesId: Long, occurrenceStart: Instant, form: TaskForm, reminders: List<TaskReminder>?) = tx {
        val master = seriesMaster(seriesId)
        // ⚠️ The overrides too, where they still carry the series' reminders.
        // A fork copies them as a snapshot — completing one occurrence is
        // enough — and each override uploads its own VALARMs, so leaving them
        // would keep ringing those occurrences at the old lead everywhere. One
        // whose reminders were set for that occurrence alone keeps them.
        if (reminders != null) {
            val before = storedReminders(master.id)
            tasks.overrides(master.id)
                .filter { storedReminders(it.id) == before }
                .forEach {
                    writeReminders(it.id, reminders)
                    if (form.remindersChosen && reminders.isEmpty()) alarms.setRemindersOff(it.id, true)
                }
        }
        writeReminders(master.id, reminders)
        val (start, due) = resolvedTimes(master, occurrenceStart)
        val edited = TaskFormWriter.seriesEdit(master, start, due, form, clock.now(), zone())
        // A moved or re-ruled series no longer generates the anchors its
        // overrides name, so they would hang off nothing.
        val reshaped = edited.dtstart != master.dtstart || edited.due != master.due || edited.rrule != master.rrule
        if (reshaped) tasks.overrides(master.id).forEach { tasks.delete(it.id) }
        if (edited.listId != master.listId) {
            if (lists.exists(edited.listId) == 0) throw TaskWriteFailedException("move series: no list ${edited.listId}")
            move(master, edited)
        } else {
            tasks.update(edited)
        }
        writes.onWritten(setOf(master.listId, edited.listId))
    }

    override fun splitSeries(seriesId: Long, occurrenceStart: Instant, form: TaskForm, reminders: List<TaskReminder>?): Long {
        var created = seriesId
        tx {
            val master = seriesMaster(seriesId)
            val spec = master.recurrenceSpec()
            val anchor = master.recurrenceAnchor
            if (spec == null || anchor == null || occurrenceStart <= anchor) {
                updateSeries(seriesId, occurrenceStart, form, reminders)
                return@tx
            }
            val now = clock.now()
            // COUNT counts before EXDATE is applied (RFC 5545 §3.8.5.3).
            val spent = RecurrenceExpander.expand(
                spec.copy(exdate = null),
                ExpansionWindow(from = anchor, until = occurrenceStart, maxOccurrences = SPLIT_COUNT_CEILING),
            ).size
            val (start, due) = resolvedTimes(master, occurrenceStart)
            tasks.update(TaskFormWriter.endedBefore(master, occurrenceStart, now, zone()))

            val sameRule = form.rrule == master.rrule
            val rule = form.rrule?.let { if (sameRule) TaskFormWriter.remainingCount(it, spent) else it }
            val base = master.copy(
                id = 0,
                uid = UUID.randomUUID().toString(),
                href = null,
                etag = null,
                rrule = null,
                exdate = null,
                rdate = null,
                createdAt = now,
                sequence = 0,
                isDeleted = false,
                dtstart = start,
                due = due,
                status = TaskStatus.NEEDS_ACTION,
                percentComplete = null,
                completedAt = null,
                // Recurrence lines belong to the old series; everything else carries over.
                unknownProperties = master.unknownProperties?.lines()
                    ?.filterNot { line -> SERIES_LINES.any { line.startsWith(it, ignoreCase = true) } }
                    ?.joinToString("\r\n")?.ifBlank { null },
            )
            val applied = TaskFormWriter.apply(base, form.copy(percentComplete = null), now, zone())
            val delta = applied.dtstart?.let { newStart -> start?.let { newStart - it } }
            val laterExceptions = TaskFormWriter.valuesFrom(master.exdate, occurrenceStart, master.timezone ?: zone())
            val exceptions = if (rule == null) null
            else if (delta == null || delta == kotlin.time.Duration.ZERO) laterExceptions
            else TaskFormWriter.shiftValues(laterExceptions, delta, master.timezone ?: zone())
            val fresh = TaskFormWriter.withRule(applied, rule).let { if (it.rrule != null) it.copy(exdate = exceptions) else it }
            val newId = tasks.insert(fresh)
            // A new UID, so a new resource: the alarms are copies, not the same ones.
            alarms.copyToTask(master.id, newId, sameResource = false)
            writeReminders(newId, reminders)

            val unmoved = fresh.dtstart == start && fresh.due == due && sameRule
            tasks.overrides(master.id)
                .filter { it.recurrenceId != null && it.recurrenceId >= occurrenceStart }
                .forEach { override ->
                    if (unmoved && override.recurrenceId != occurrenceStart) {
                        tasks.update(
                            override.copy(masterId = newId, uid = fresh.uid, listId = fresh.listId, href = null, etag = null, isDirty = true),
                        )
                    } else {
                        tasks.delete(override.id)
                    }
                }
            created = newId
            writes.onWritten(setOf(master.listId, fresh.listId))
        }
        return created
    }

    override fun deleteOccurrence(seriesId: Long, occurrenceStart: Instant) = tx {
        tasks.override(seriesId, occurrenceStart)?.let {
            deleteTask(it.id)
            return@tx
        }
        val master = tasks.entity(seriesId) ?: return@tx
        val synced = lists.entity(master.listId)?.accountId != null
        tasks.update(TaskFormWriter.excepting(master, occurrenceStart, clock.now(), zone(), dirty = synced))
        writes.onWritten(setOf(master.listId))
    }

    override fun deleteFollowing(seriesId: Long, occurrenceStart: Instant) = tx {
        val master = tasks.entity(seriesId) ?: return@tx
        val anchor = master.recurrenceAnchor
        if (anchor == null || occurrenceStart <= anchor) {
            deleteWithSubtasks(seriesId)
            return@tx
        }
        tasks.update(TaskFormWriter.endedBefore(master, occurrenceStart, clock.now(), zone()))
        tasks.overrides(seriesId)
            .filter { it.recurrenceId != null && it.recurrenceId >= occurrenceStart }
            .forEach { tasks.delete(it.id) }
        writes.onWritten(setOf(master.listId))
    }

    private inline fun tx(crossinline block: () -> Unit) = database.runInTransaction(Runnable { block() })

    private fun seriesMaster(seriesId: Long): TaskEntity {
        val row = tasks.entity(seriesId) ?: throw TaskWriteFailedException("series $seriesId")
        return row.masterId?.let { tasks.entity(it) } ?: row
    }

    /** The occurrence's own times: its override's if it has one, else generated. */
    private fun resolvedTimes(master: TaskEntity, occurrenceStart: Instant): Pair<Instant?, Instant?> =
        tasks.override(master.id, occurrenceStart)?.let { it.dtstart to it.due }
            ?: occurrenceTimes(master, occurrenceStart)

    /**
     * Writes one occurrence as a `RECURRENCE-ID` override — RFC 5545's model, and
     * what every other CalDAV client expects to receive. The dmfs provider
     * detached the occurrence into a brand-new task with its own UID instead,
     * which is the model least compatible with sync; the override shares its
     * master's UID, which is exactly what makes it an override.
     */
    override fun updateInstance(taskId: Long, occurrenceStart: Instant, form: TaskForm, reminders: List<TaskReminder>?) = tx {
        val master = tasks.entity(taskId) ?: throw TaskWriteFailedException("update instance $taskId")
        val now = clock.now()
        val existing = tasks.override(taskId, occurrenceStart)
        if (existing != null) {
            // The same rule the fork below applies: the list and parent are the
            // master's, whatever the form was carrying.
            val edited = TaskFormWriter.apply(existing, form, now, zone())
            tasks.update(edited.copy(listId = master.listId, parentId = master.parentId))
            writeReminders(existing.id, reminders)
            writes.onWritten(setOf(master.listId))
            return@tx
        }
        val (start, due) = occurrenceTimes(master, occurrenceStart)
        val fork = TaskFormWriter.apply(
            newOverride(master, taskId, occurrenceStart, start, due),
            form,
            now,
            zone(),
        )
        // The list and parent come from the master: moving one occurrence between
        // lists or parents is not something the override model expresses.
        val id = tasks.insert(fork.copy(listId = master.listId, parentId = master.parentId))
        // The occurrence starts out with the series' reminders and then takes
        // the form's — the master's own are never touched.
        alarms.copyToTask(taskId, id, sameResource = true)
        writeReminders(id, reminders)
        writes.onWritten(setOf(master.listId))
    }

    override fun setReminders(taskId: Long, reminders: List<TaskReminder>) = tx {
        if (!alarms.syncForTask(taskId, reminders)) return@tx
        // The reminders are part of the VTODO now, so changing them is an edit.
        val current = tasks.entity(taskId) ?: return@tx
        tasks.update(current.copy(isDirty = true, lastModified = clock.now()))
        writes.onWritten(setOf(current.listId))
    }

    override fun setCancelled(taskId: Long, cancelled: Boolean) {
        val current = tasks.entity(taskId) ?: throw TaskWriteFailedException("cancel task $taskId")
        tasks.update(TaskFormWriter.cancelled(current, cancelled, clock.now()))
        writes.onWritten(setOf(current.listId))
    }

    override fun setCompleted(taskId: Long, completed: Boolean) {
        val current = tasks.entity(taskId) ?: throw TaskWriteFailedException("complete task $taskId")
        tasks.update(TaskFormWriter.completed(current, completed, clock.now()))
        writes.onWritten(setOf(current.listId))
    }

    /**
     * Ticking one occurrence forks a `RECURRENCE-ID` override carrying the
     * completion — the same model [updateInstance] writes. Writing the status onto
     * the master instead would close the series: the master is what
     * [TaskDao.tasks] filters on, so every occurrence, past and future, would
     * leave every list at once.
     */
    override fun setCompletedInstance(taskId: Long, occurrenceStart: Instant, completed: Boolean) =
        writeInstance(taskId, occurrenceStart, "complete") { TaskFormWriter.completed(it, completed, clock.now()) }

    /** Same override model as [setCompletedInstance]. */
    override fun setCancelledInstance(taskId: Long, occurrenceStart: Instant, cancelled: Boolean) =
        writeInstance(taskId, occurrenceStart, "cancel") { TaskFormWriter.cancelled(it, cancelled, clock.now()) }

    /** Applies [write] to the override for [occurrenceStart], forking one if there is none yet. */
    private fun writeInstance(
        taskId: Long,
        occurrenceStart: Instant,
        what: String,
        write: (TaskEntity) -> TaskEntity,
    ) {
        val master = tasks.entity(taskId) ?: throw TaskWriteFailedException("$what instance $taskId")
        // Not a series master — an override, or a plain task the caller handed an
        // anchor for. Either way this row *is* the occurrence.
        if (master.recurrenceSpec() == null) {
            tasks.update(write(master))
            writes.onWritten(setOf(master.listId))
            return
        }

        tasks.override(taskId, occurrenceStart)?.let {
            tasks.update(write(it))
            writes.onWritten(setOf(master.listId))
            return
        }
        val (start, due) = occurrenceTimes(master, occurrenceStart)
        val id = tasks.insert(write(newOverride(master, taskId, occurrenceStart, start, due)))
        alarms.copyToTask(taskId, id, sameResource = true)
        writes.onWritten(setOf(master.listId))
    }

    /**
     * A blank override row for one occurrence of [master]: same UID (that is what
     * makes it an override rather than a separate task), the series fields
     * stripped, and no `href`/`etag` because the server has never seen it.
     */
    private fun newOverride(
        master: TaskEntity,
        masterId: Long,
        occurrenceStart: Instant,
        start: Instant?,
        due: Instant?,
    ): TaskEntity = master.copy(
        id = 0,
        masterId = masterId,
        recurrenceId = occurrenceStart,
        dtstart = start,
        due = due,
        rrule = null,
        rdate = null,
        exdate = null,
        href = null,
        etag = null,
    )

    /**
     * Hard delete for a row no server knows about, tombstone for one that is
     * still owed to a collection — and an `EXDATE` when what is being deleted is
     * a single occurrence of a series.
     *
     * ⚠️ Removing an override does not delete the occurrence, it *un-overrides*
     * it: per RFC 5545 the master's `RRULE` regenerates it as a plain instance,
     * on the server and on every other client. The exception has to be written
     * down on the master, which is also what keeps the occurrence hidden in a
     * device-only list, where there is no tombstone to hide it.
     */
    override fun deleteTask(taskId: Long) {
        val current = tasks.entity(taskId) ?: return
        val listAccount = lists.entity(current.listId)?.accountId
        val masterId = current.masterId
        val occurrence = current.recurrenceId
        if (masterId != null && occurrence != null) {
            tasks.entity(masterId)?.let { master ->
                tasks.update(
                    TaskFormWriter.excepting(
                        master,
                        occurrence,
                        clock.now(),
                        zone(),
                        // A device-only list owes nobody a PUT.
                        dirty = listAccount != null,
                    ),
                )
            }
            // The master's EXDATE *is* the deletion, so the override has no job
            // left. Leaving a tombstone behind would upload a body with the
            // occurrence merely absent, which says the opposite.
            tasks.delete(taskId)
            writes.onWritten(setOf(current.listId))
            return
        }
        if (listAccount == null) tasks.delete(taskId) else tasks.markDeleted(taskId, clock.now())
        writes.onWritten(setOf(current.listId))
    }

    // --- whole-row writes, for the published provider --------------------------

    /** Inserts [entity] as it stands, for a client that wrote the row itself. */
    internal fun insertEntity(entity: TaskEntity): Long {
        if (lists.exists(entity.listId) == 0) throw TaskWriteFailedException("insert task: no list ${entity.listId}")
        return tasks.insert(entity).also { writes.onWritten(setOf(entity.listId)) }
    }

    /**
     * Writes [edited] over [current] under the rules [updateTask] and
     * [updateSeries] keep: a task moves lists through [TaskMove], an override
     * keeps its master's list and parent, and a series whose anchor or rule
     * changed drops the overrides it no longer generates.
     */
    internal fun replaceEntity(current: TaskEntity, edited: TaskEntity) {
        tx {
            val reshaped = current.masterId == null && current.recurrenceSpec() != null &&
                (edited.dtstart != current.dtstart || edited.due != current.due || edited.rrule != current.rrule)
            if (reshaped) tasks.overrides(current.id).forEach { tasks.delete(it.id) }
            when {
                current.masterId != null -> tasks.update(edited.copy(listId = current.listId, parentId = current.parentId))
                edited.listId != current.listId -> {
                    if (lists.exists(edited.listId) == 0) throw TaskWriteFailedException("move task: no list ${edited.listId}")
                    move(current, edited)
                }
                else -> tasks.update(edited)
            }
        }
        writes.onWritten(setOf(current.listId, edited.listId))
    }

    /** Adds, changes or removes one reminder, and marks its task changed so the reminder syncs. */
    internal fun writeAlarm(taskId: Long, write: () -> Unit) {
        val task = tasks.entity(taskId) ?: throw TaskWriteFailedException("alarm on task $taskId")
        tx {
            write()
            tasks.update(task.copy(lastModified = clock.now(), isDirty = true))
        }
        writes.onWritten(setOf(task.listId))
    }

    override fun createLocalList(name: String, color: Int): Long =
        lists.insert(TaskListEntity(name = name.trim(), color = color))

    /**
     * ⚠️ Device-only lists. An account-backed collection is renamed through
     * [com.aiexile.agendula.data.sync.RemoteLists], which sends
     * the PROPPATCH first and writes here only once the server has taken it.
     *
     * This used to set `is_dirty` for an account list instead, on the theory
     * that a later sync would push it. Nothing ever read that flag: the rename
     * never left the phone, and the next run that re-read the collection's
     * `displayname` quietly put the old name back.
     */
    override fun updateList(listId: Long, name: String, color: Int) {
        val current = lists.entity(listId) ?: throw TaskWriteFailedException("update list $listId")
        lists.update(current.copy(name = name.trim(), color = color))
    }

    override fun reorderLists(listIds: List<Long>) = tx {
        listIds.forEachIndexed { index, id -> lists.setSortOrder(id, index) }
    }

    /** `tasks.list_id` is `ON DELETE CASCADE`, so the list's tasks go with it. */
    override fun deleteList(listId: Long) = lists.delete(listId)

    // --- observation ----------------------------------------------------------

    override fun registerObserver(onChange: () -> Unit): AutoCloseable {
        val observer = object : InvalidationTracker.Observer(OBSERVED_TABLES) {
            override fun onInvalidated(tables: Set<String>) = onChange()
        }
        database.invalidationTracker.addObserver(observer)
        return AutoCloseable { database.invalidationTracker.removeObserver(observer) }
    }

    private fun zone(): String = ZoneId.systemDefault().id
}

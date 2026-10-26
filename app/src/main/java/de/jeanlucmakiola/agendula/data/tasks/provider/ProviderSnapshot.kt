package de.jeanlucmakiola.agendula.data.tasks.provider

import de.jeanlucmakiola.agendula.data.tasks.TasksContract
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Alarm
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Category
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Instances
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Lists
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Properties
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Tasks
import de.jeanlucmakiola.agendula.data.tasks.room.AlarmReference
import de.jeanlucmakiola.agendula.data.tasks.room.TaskAlarmEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TaskEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TaskListRow
import de.jeanlucmakiola.agendula.data.tasks.room.seriesOccurrences
import de.jeanlucmakiola.agendula.domain.TaskStatus
import de.jeanlucmakiola.agendula.domain.toInt
import java.time.ZoneId
import kotlin.time.Instant

typealias ProviderRow = Map<String, Any?>

/**
 * Ids that stay put for as long as what they name exists, so a client can read
 * an id and write to it later, or several in one batch. The one exception is
 * two tags on one task whose spellings hash alike (about one in 2³¹): removing
 * the one that sorts first moves the other onto its id.
 *
 * An instance is its series and its original time; a plain task's one
 * instance has none. An alarm property is the alarm row; a tag property is its
 * task and its spelling. Alarms take even ids and tags odd ones, so the two
 * never meet.
 */
object ProviderIds {

    fun instance(seriesId: Long, originalTime: Instant?): Long =
        (seriesId shl 32) or ((originalTime?.epochSeconds ?: 0L) and 0xFFFFFFFFL)

    fun instanceSeries(instanceId: Long): Long = instanceId ushr 32

    fun instanceSeconds(instanceId: Long): Long = instanceId and 0xFFFFFFFFL

    fun alarm(alarmId: Long): Long = alarmId shl 1

    fun isAlarm(propertyId: Long): Boolean = propertyId and 1L == 0L

    fun alarmRow(propertyId: Long): Long = propertyId ushr 1

    /** The property id of each of [taskId]'s [categories], by spelling. */
    fun categories(taskId: Long, categories: List<String>): Map<String, Long> =
        categories(taskId, categories, ::tagHash)

    internal fun categories(taskId: Long, categories: List<String>, hash: (String) -> Long): Map<String, Long> {
        val used = HashSet<Long>()
        // Two spellings on one task with one hash: the one that sorts later takes
        // the next free slot, wrapping inside the tag bits so it never reaches the
        // task's. Sorting keeps that independent of the tags' order.
        val ids = categories.sorted().associateWith { name ->
            var bits = hash(name) and TAG_BITS
            while (!used.add(bits)) bits = (bits + 1) and TAG_BITS
            (((taskId shl 31) or bits) shl 1) or 1L
        }
        return categories.associateWith(ids::getValue)
    }

    fun categoryTask(propertyId: Long): Long = propertyId ushr 32

    private const val TAG_BITS = 0x7FFFFFFFL

    /** 64-bit FNV-1a over the spelling's UTF-8, folded to 31 bits. */
    private fun tagHash(name: String): Long {
        var hash = -0x340d631b7bdddcdbL
        name.encodeToByteArray().forEach { byte ->
            hash = (hash xor (byte.toLong() and 0xFF)) * 0x100000001b3L
        }
        return (hash xor (hash ushr 31) xor (hash ushr 62)) and TAG_BITS
    }
}

/** The Room store laid out as OpenTasks' tables, ready to be queried. */
class ProviderSnapshot(val rows: Map<ProviderTable, List<ProviderRow>>) {

    companion object {
        val EMPTY = ProviderSnapshot(ProviderTable.entries.associateWith { emptyList() })

        /**
         * Lays out the store. [tasks] must hold no tombstones. [accountType] is
         * what a list attached to an account reports; a device-only list reports
         * OpenTasks' local account so clients treat it the way they already do.
         */
        fun build(
            lists: List<TaskListRow>,
            tasks: List<TaskEntity>,
            alarms: List<TaskAlarmEntity>,
            accountType: String,
            now: Instant,
            zone: ZoneId = ZoneId.systemDefault(),
        ): ProviderSnapshot {
            val listsById = lists.associateBy { it.list.id }
            val alarmsByTask = alarms.groupBy { it.taskId }
            val taskRows = tasks.mapNotNull { task ->
                val list = listsById[task.listId] ?: return@mapNotNull null
                task.id to taskColumns(task, list, alarmsByTask[task.id].orEmpty().isNotEmpty(), accountType)
            }.toMap()

            val overrides = tasks.filter { it.masterId != null }.groupBy { it.masterId }
            val instances = tasks.filter { it.masterId == null && it.id in taskRows }.flatMap { task ->
                occurrences(task, overrides[task.id].orEmpty(), now).mapNotNull { occurrence ->
                    val columns = taskRows[occurrence.row.id] ?: return@mapNotNull null
                    columns + instanceColumns(ProviderIds.instance(task.id, occurrence.originalTime), occurrence, zone)
                }
            }

            return ProviderSnapshot(
                mapOf(
                    ProviderTable.LISTS to lists.map { listColumns(it, accountType) },
                    ProviderTable.TASKS to taskRows.map { (id, columns) -> columns + (Tasks.ID to id) },
                    ProviderTable.INSTANCES to instances,
                    ProviderTable.PROPERTIES to properties(tasks.filter { it.id in taskRows }, alarms),
                ),
            )
        }

        private fun listColumns(row: TaskListRow, accountType: String): ProviderRow = mapOf(
            Lists.ID to row.list.id,
            Lists.NAME to row.list.name,
            Lists.COLOR to row.list.color,
            Lists.ACCESS_LEVEL to 0,
            Lists.VISIBLE to row.list.isVisible.bit,
            Lists.SYNC_ENABLED to row.list.isSynced.bit,
            Lists.OWNER to row.list.owner,
            Lists.ACCOUNT_NAME to accountName(row),
            Lists.ACCOUNT_TYPE to accountTypeOf(row, accountType),
        )

        private fun taskColumns(task: TaskEntity, list: TaskListRow, hasAlarms: Boolean, accountType: String) =
            mapOf(
                Tasks.LIST_ID to task.listId,
                Tasks.LIST_NAME to list.list.name,
                Tasks.LIST_COLOR to list.list.color,
                Tasks.LIST_OWNER to list.list.owner,
                Tasks.LIST_ACCESS_LEVEL to 0,
                Tasks.VISIBLE to list.list.isVisible.bit,
                Tasks.ACCOUNT_NAME to accountName(list),
                Tasks.ACCOUNT_TYPE to accountTypeOf(list, accountType),
                Tasks.UID to task.uid,
                Tasks.DELETED to 0,
                Tasks.TITLE to task.title,
                Tasks.DESCRIPTION to task.description,
                Tasks.LOCATION to task.location,
                Tasks.URL to task.url,
                Tasks.PRIORITY to task.priority,
                Tasks.CLASSIFICATION to task.classification,
                Tasks.STATUS to task.status.toInt(),
                Tasks.PERCENT_COMPLETE to task.percentComplete,
                Tasks.COMPLETED to task.completedAt?.toEpochMilliseconds(),
                Tasks.IS_CLOSED to (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.CANCELLED).bit,
                Tasks.IS_NEW to (task.status == TaskStatus.NEEDS_ACTION && task.percentComplete == null).bit,
                Tasks.TASK_COLOR to task.color,
                Tasks.DTSTART to task.dtstart?.toEpochMilliseconds(),
                Tasks.DUE to task.due?.toEpochMilliseconds(),
                Tasks.DURATION to task.duration,
                Tasks.IS_ALLDAY to task.isAllDay.bit,
                Tasks.TZ to task.timezone,
                Tasks.RRULE to task.rrule,
                Tasks.RDATE to task.rdate,
                Tasks.EXDATE to task.exdate,
                Tasks.ORIGINAL_INSTANCE_ID to task.masterId,
                Tasks.ORIGINAL_INSTANCE_TIME to task.recurrenceId?.toEpochMilliseconds(),
                Tasks.ORIGINAL_INSTANCE_ALLDAY to task.masterId?.let { task.isAllDay.bit },
                Tasks.PARENT_ID to task.parentId,
                Tasks.SORTING to task.sortOrder,
                Tasks.HAS_ALARMS to hasAlarms.bit,
                Tasks.CREATED to task.createdAt?.toEpochMilliseconds(),
                Tasks.LAST_MODIFIED to task.lastModified?.toEpochMilliseconds(),
            )

        /** One instance: the row it reads from, its times, and where it sits in its series. */
        private class Occurrence(
            val row: TaskEntity,
            val start: Instant?,
            val due: Instant?,
            val originalTime: Instant?,
            val distance: Int,
            val recurring: Boolean,
        )

        private fun occurrences(master: TaskEntity, overrides: List<TaskEntity>, now: Instant): List<Occurrence> {
            val series = seriesOccurrences(master, overrides, now)
                ?: return listOf(Occurrence(master, master.dtstart, master.due, null, 0, recurring = false))
            return series.mapIndexed { index, occurrence ->
                Occurrence(
                    row = occurrence.override ?: master,
                    start = occurrence.start,
                    due = occurrence.due,
                    originalTime = occurrence.anchor,
                    distance = occurrence.distance ?: index,
                    recurring = true,
                )
            }
        }

        private fun instanceColumns(id: Long, occurrence: Occurrence, zone: ZoneId): ProviderRow {
            val allDay = occurrence.row.isAllDay
            val start = occurrence.start?.toEpochMilliseconds()
            val due = occurrence.due?.toEpochMilliseconds()
            return mapOf(
                Tasks.ID to id,
                Instances.TASK_ID to occurrence.row.id,
                Instances.INSTANCE_START to start,
                Instances.INSTANCE_START_SORTING to start?.let { sorting(it, allDay, zone) },
                Instances.INSTANCE_DUE to due,
                Instances.INSTANCE_DUE_SORTING to due?.let { sorting(it, allDay, zone) },
                Instances.INSTANCE_DURATION to if (start != null && due != null) due - start else null,
                Instances.INSTANCE_ORIGINAL_TIME to occurrence.originalTime?.toEpochMilliseconds(),
                Instances.DISTANCE_FROM_CURRENT to occurrence.distance,
                Instances.IS_RECURRING to occurrence.recurring.bit,
            )
        }

        /**
         * An all-day date is stored as UTC midnight; OpenTasks' sorting columns
         * move it to local midnight so it sorts among the timed tasks of that day.
         */
        private fun sorting(millis: Long, allDay: Boolean, zone: ZoneId): Long {
            if (!allDay) return millis
            val offset = zone.rules.getOffset(java.time.Instant.ofEpochMilli(millis)).totalSeconds * 1000L
            return millis - offset
        }

        private fun properties(tasks: List<TaskEntity>, alarms: List<TaskAlarmEntity>): List<ProviderRow> {
            val live = tasks.mapTo(HashSet()) { it.id }
            val alarmRows = alarms.filter { it.taskId in live }.map {
                mapOf(
                    Properties.PROPERTY_ID to ProviderIds.alarm(it.id),
                    Properties.TASK_ID to it.taskId,
                    Properties.MIMETYPE to Alarm.MIMETYPE,
                    Alarm.MINUTES_BEFORE to it.minutesBefore.toString(),
                    Alarm.REFERENCE to when (it.reference) {
                        AlarmReference.DUE -> Alarm.REFERENCE_DUE
                        AlarmReference.START -> Alarm.REFERENCE_START
                    }.toString(),
                    Alarm.MESSAGE to it.message,
                    Alarm.ALARM_TYPE to Alarm.TYPE_MESSAGE.toString(),
                )
            }
            val categoryRows = tasks.flatMap { task ->
                ProviderIds.categories(task.id, task.categories).map { (name, id) ->
                    mapOf(
                        Properties.PROPERTY_ID to id,
                        Properties.TASK_ID to task.id,
                        Properties.MIMETYPE to Category.MIMETYPE,
                        Category.NAME to name,
                    )
                }
            }
            return alarmRows + categoryRows
        }

        private fun accountName(row: TaskListRow): String =
            row.accountDisplayName ?: TasksContract.LOCAL_ACCOUNT_NAME

        private fun accountTypeOf(row: TaskListRow, accountType: String): String =
            if (row.list.accountId == null) TasksContract.LOCAL_ACCOUNT_TYPE else accountType

        private val Boolean.bit: Int get() = if (this) 1 else 0
    }
}

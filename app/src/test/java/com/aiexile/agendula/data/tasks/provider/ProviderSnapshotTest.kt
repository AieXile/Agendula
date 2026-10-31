package com.aiexile.agendula.data.tasks.provider

import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.data.tasks.TasksContract
import com.aiexile.agendula.data.tasks.TasksContract.Alarm
import com.aiexile.agendula.data.tasks.TasksContract.Category
import com.aiexile.agendula.data.tasks.TasksContract.Instances
import com.aiexile.agendula.data.tasks.TasksContract.Lists
import com.aiexile.agendula.data.tasks.TasksContract.Properties
import com.aiexile.agendula.data.tasks.TasksContract.Tasks
import com.aiexile.agendula.data.tasks.room.AlarmReference
import com.aiexile.agendula.data.tasks.room.TaskAlarmEntity
import com.aiexile.agendula.data.tasks.room.TaskEntity
import com.aiexile.agendula.data.tasks.room.TaskListEntity
import com.aiexile.agendula.data.tasks.room.TaskListRow
import com.aiexile.agendula.domain.TaskStatus
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ProviderSnapshotTest {

    private val now = Instant.parse("2026-10-10T08:00:00Z")
    private val local = TaskListRow(TaskListEntity(id = 1, name = "Home", color = 0xFF00FF00.toInt()), null)
    private val synced = TaskListRow(TaskListEntity(id = 2, name = "Work", color = 0, accountId = 5), "Nextcloud")

    private fun build(tasks: List<TaskEntity>, alarms: List<TaskAlarmEntity> = emptyList()) =
        ProviderSnapshot.build(
            lists = listOf(local, synced),
            tasks = tasks,
            alarms = alarms,
            accountType = "caldav-type",
            now = now,
            zone = ZoneId.of("Europe/Berlin"),
        )

    @Test
    fun `lists report the local account for device-only lists and ours for synced ones`() {
        val rows = build(emptyList()).rows.getValue(ProviderTable.LISTS)

        assertThat(rows.map { it[Lists.ACCOUNT_TYPE] })
            .containsExactly(TasksContract.LOCAL_ACCOUNT_TYPE, "caldav-type").inOrder()
        assertThat(rows.map { it[Lists.ACCOUNT_NAME] })
            .containsExactly(TasksContract.LOCAL_ACCOUNT_NAME, "Nextcloud").inOrder()
    }

    @Test
    fun `a plain task is one task row and one instance`() {
        val due = now + 1.days
        val task = TaskEntity(id = 7, listId = 1, uid = "u7", title = "Milk", due = due, priority = 3)

        val snapshot = build(listOf(task))
        val row = snapshot.rows.getValue(ProviderTable.TASKS).single()
        val instance = snapshot.rows.getValue(ProviderTable.INSTANCES).single()

        assertThat(row[Tasks.ID]).isEqualTo(7L)
        assertThat(row[Tasks.PRIORITY]).isEqualTo(3)
        assertThat(row[Tasks.LIST_NAME]).isEqualTo("Home")
        assertThat(row[Tasks.IS_CLOSED]).isEqualTo(0)
        assertThat(row[Tasks.IS_NEW]).isEqualTo(1)
        assertThat(instance[Instances.TASK_ID]).isEqualTo(7L)
        assertThat(instance[Instances.INSTANCE_DUE]).isEqualTo(due.toEpochMilliseconds())
        assertThat(instance[Instances.IS_RECURRING]).isEqualTo(0)
        assertThat(instance[Instances.DISTANCE_FROM_CURRENT]).isEqualTo(0)
    }

    @Test
    fun `a completed task is closed`() {
        val task = TaskEntity(id = 1, listId = 1, uid = "u", status = TaskStatus.COMPLETED, completedAt = now)

        val row = build(listOf(task)).rows.getValue(ProviderTable.TASKS).single()

        assertThat(row[Tasks.IS_CLOSED]).isEqualTo(1)
        assertThat(row[Tasks.STATUS]).isEqualTo(TasksContract.STATUS_COMPLETED)
        assertThat(row[Tasks.COMPLETED]).isEqualTo(now.toEpochMilliseconds())
    }

    @Test
    fun `an override replaces the occurrence it names`() {
        val start = Instant.parse("2026-10-09T09:00:00Z")
        val master = TaskEntity(
            id = 10, listId = 1, uid = "s", title = "Run", dtstart = start, due = start + 1.hours,
            rrule = "FREQ=DAILY;COUNT=3", timezone = "UTC",
        )
        val second = start + 1.days
        val override = master.copy(
            id = 11, rrule = null, recurrenceId = second, masterId = 10, title = "Run long",
            dtstart = second + 2.hours, due = second + 4.hours,
        )

        val snapshot = build(listOf(master, override))
        val instances = snapshot.rows.getValue(ProviderTable.INSTANCES)

        assertThat(instances.map { it[Instances.TASK_ID] }).containsExactly(10L, 11L, 10L).inOrder()
        assertThat(instances.map { it[Tasks.TITLE] }).containsExactly("Run", "Run long", "Run").inOrder()
        assertThat(instances[1][Instances.INSTANCE_ORIGINAL_TIME]).isEqualTo(second.toEpochMilliseconds())
        assertThat(instances[1][Instances.INSTANCE_DURATION]).isEqualTo(2.hours.inWholeMilliseconds)
        assertThat(instances.map { it[Tasks.ID] }).containsNoDuplicates()

        val overrideRow = snapshot.rows.getValue(ProviderTable.TASKS).single { it[Tasks.ID] == 11L }
        assertThat(overrideRow[Tasks.ORIGINAL_INSTANCE_ID]).isEqualTo(10L)
        assertThat(overrideRow[Tasks.ORIGINAL_INSTANCE_TIME]).isEqualTo(second.toEpochMilliseconds())
    }

    @Test
    fun `an all-day date sorts at local midnight`() {
        val day = Instant.parse("2026-10-12T00:00:00Z")
        val task = TaskEntity(id = 1, listId = 1, uid = "u", due = day, isAllDay = true)

        val instance = build(listOf(task)).rows.getValue(ProviderTable.INSTANCES).single()

        assertThat(instance[Instances.INSTANCE_DUE]).isEqualTo(day.toEpochMilliseconds())
        assertThat(instance[Instances.INSTANCE_DUE_SORTING]).isEqualTo((day - 2.hours).toEpochMilliseconds())
    }

    @Test
    fun `alarms and tags become properties`() {
        val task = TaskEntity(id = 3, listId = 2, uid = "u", categories = listOf("errand", "home"))
        val alarm = TaskAlarmEntity(id = 1, taskId = 3, minutesBefore = 15)

        val snapshot = build(listOf(task), listOf(alarm))
        val properties = snapshot.rows.getValue(ProviderTable.PROPERTIES)

        assertThat(snapshot.rows.getValue(ProviderTable.TASKS).single()[Tasks.HAS_ALARMS]).isEqualTo(1)
        val alarmRow = properties.single { it[Properties.MIMETYPE] == Alarm.MIMETYPE }
        assertThat(alarmRow[Alarm.MINUTES_BEFORE]).isEqualTo("15")
        assertThat(alarmRow[Alarm.REFERENCE]).isEqualTo(Alarm.REFERENCE_DUE.toString())
        assertThat(properties.filter { it[Properties.MIMETYPE] == Category.MIMETYPE }.map { it[Category.NAME] })
            .containsExactly("errand", "home")
        assertThat(properties.map { it[Properties.PROPERTY_ID] }).containsNoDuplicates()
    }

    @Test
    fun `every row carries exactly its table's columns`() {
        val task = TaskEntity(id = 3, listId = 1, uid = "u", categories = listOf("a"))
        val snapshot = build(listOf(task), listOf(TaskAlarmEntity(id = 1, taskId = 3, minutesBefore = 0)))

        ProviderTable.entries.forEach { table ->
            val columns = table.columns.map { it.first }.toSet()
            snapshot.rows.getValue(table).forEach { assertThat(columns).containsAtLeastElementsIn(it.keys) }
        }
    }

    @Test
    fun `the empty snapshot has every table and no rows`() {
        assertThat(ProviderSnapshot.EMPTY.rows.keys).containsExactlyElementsIn(ProviderTable.entries)
        assertThat(ProviderSnapshot.EMPTY.rows.values.flatten()).isEmpty()
    }

    @Test
    fun `lists carry their name, colour and flags`() {
        val hidden = TaskListRow(TaskListEntity(id = 3, name = "Old", color = 7, isVisible = false, isSynced = false), null)

        val row = ProviderSnapshot.build(listOf(hidden), emptyList(), emptyList(), "t", now)
            .rows.getValue(ProviderTable.LISTS).single()

        assertThat(row[Lists.ID]).isEqualTo(3L)
        assertThat(row[Lists.NAME]).isEqualTo("Old")
        assertThat(row[Lists.COLOR]).isEqualTo(7)
        assertThat(row[Lists.VISIBLE]).isEqualTo(0)
        assertThat(row[Lists.SYNC_ENABLED]).isEqualTo(0)
    }

    @Test
    fun `a task reports its list's account`() {
        val task = TaskEntity(id = 1, listId = 2, uid = "u")

        val row = build(listOf(task)).rows.getValue(ProviderTable.TASKS).single()

        assertThat(row[Tasks.LIST_NAME]).isEqualTo("Work")
        assertThat(row[Tasks.ACCOUNT_NAME]).isEqualTo("Nextcloud")
        assertThat(row[Tasks.ACCOUNT_TYPE]).isEqualTo("caldav-type")
    }

    @Test
    fun `a task whose list is gone is left out everywhere`() {
        val orphan = TaskEntity(id = 4, listId = 99, uid = "u", categories = listOf("x"))
        val alarm = TaskAlarmEntity(id = 1, taskId = 4, minutesBefore = 5)

        val snapshot = build(listOf(orphan), listOf(alarm))

        assertThat(snapshot.rows.getValue(ProviderTable.TASKS)).isEmpty()
        assertThat(snapshot.rows.getValue(ProviderTable.INSTANCES)).isEmpty()
        assertThat(snapshot.rows.getValue(ProviderTable.PROPERTIES)).isEmpty()
    }

    @Test
    fun `task fields map to their OpenTasks columns`() {
        val start = Instant.parse("2026-10-11T09:00:00Z")
        val task = TaskEntity(
            id = 5, listId = 1, uid = "uid-5", title = "T", description = "D", location = "L", url = "https://x",
            color = 42, classification = 1, percentComplete = 50, dtstart = start, due = start + 1.hours,
            timezone = "Europe/Berlin", parentId = 2, sortOrder = 3, createdAt = now, lastModified = now,
        )

        val row = build(listOf(task)).rows.getValue(ProviderTable.TASKS).single()

        assertThat(row[Tasks.UID]).isEqualTo("uid-5")
        assertThat(row[Tasks.TITLE]).isEqualTo("T")
        assertThat(row[Tasks.DESCRIPTION]).isEqualTo("D")
        assertThat(row[Tasks.LOCATION]).isEqualTo("L")
        assertThat(row[Tasks.URL]).isEqualTo("https://x")
        assertThat(row[Tasks.TASK_COLOR]).isEqualTo(42)
        assertThat(row[Tasks.CLASSIFICATION]).isEqualTo(1)
        assertThat(row[Tasks.PERCENT_COMPLETE]).isEqualTo(50)
        assertThat(row[Tasks.DTSTART]).isEqualTo(start.toEpochMilliseconds())
        assertThat(row[Tasks.DUE]).isEqualTo((start + 1.hours).toEpochMilliseconds())
        assertThat(row[Tasks.TZ]).isEqualTo("Europe/Berlin")
        assertThat(row[Tasks.IS_ALLDAY]).isEqualTo(0)
        assertThat(row[Tasks.PARENT_ID]).isEqualTo(2L)
        assertThat(row[Tasks.SORTING]).isEqualTo(3)
        assertThat(row[Tasks.CREATED]).isEqualTo(now.toEpochMilliseconds())
        assertThat(row[Tasks.LAST_MODIFIED]).isEqualTo(now.toEpochMilliseconds())
        assertThat(row[Tasks.DELETED]).isEqualTo(0)
        assertThat(row[Tasks.HAS_ALARMS]).isEqualTo(0)
        assertThat(row[Tasks.ORIGINAL_INSTANCE_ID]).isNull()
        assertThat(row[Tasks.ORIGINAL_INSTANCE_ALLDAY]).isNull()
    }

    @Test
    fun `a task with progress is no longer new`() {
        val task = TaskEntity(id = 1, listId = 1, uid = "u", percentComplete = 10)

        assertThat(build(listOf(task)).rows.getValue(ProviderTable.TASKS).single()[Tasks.IS_NEW]).isEqualTo(0)
    }

    @Test
    fun `a cancelled task is closed`() {
        val task = TaskEntity(id = 1, listId = 1, uid = "u", status = TaskStatus.CANCELLED)

        val row = build(listOf(task)).rows.getValue(ProviderTable.TASKS).single()

        assertThat(row[Tasks.IS_CLOSED]).isEqualTo(1)
        assertThat(row[Tasks.STATUS]).isEqualTo(TasksContract.STATUS_CANCELLED)
    }

    @Test
    fun `a series expands into one instance per occurrence`() {
        val start = Instant.parse("2026-10-09T09:00:00Z")
        val master = TaskEntity(
            id = 10, listId = 1, uid = "s", dtstart = start, due = start + 1.hours,
            rrule = "FREQ=DAILY;COUNT=3", timezone = "UTC",
        )

        val snapshot = build(listOf(master))
        val instances = snapshot.rows.getValue(ProviderTable.INSTANCES)

        assertThat(snapshot.rows.getValue(ProviderTable.TASKS)).hasSize(1)
        assertThat(instances.map { it[Instances.TASK_ID] }).containsExactly(10L, 10L, 10L)
        assertThat(instances.map { it[Instances.IS_RECURRING] }).containsExactly(1, 1, 1)
        assertThat(instances.map { it[Instances.INSTANCE_START] }).containsExactlyElementsIn(
            (0..2).map { (start + it.days).toEpochMilliseconds() },
        ).inOrder()
        assertThat(instances.map { it[Instances.INSTANCE_ORIGINAL_TIME] })
            .isEqualTo(instances.map { it[Instances.INSTANCE_START] })
        assertThat(instances.map { it[Instances.INSTANCE_DURATION] }).containsExactly(
            1.hours.inWholeMilliseconds, 1.hours.inWholeMilliseconds, 1.hours.inWholeMilliseconds,
        )
        // 10-09 is past, 10-10 09:00 is the next one at or after 08:00.
        assertThat(instances.map { it[Instances.DISTANCE_FROM_CURRENT] }).containsExactly(-1, 0, 1).inOrder()
    }

    @Test
    fun `a due-anchored series has no start`() {
        val due = Instant.parse("2026-10-12T17:00:00Z")
        val master = TaskEntity(id = 10, listId = 1, uid = "s", due = due, rrule = "FREQ=WEEKLY;COUNT=2", timezone = "UTC")

        val instances = build(listOf(master)).rows.getValue(ProviderTable.INSTANCES)

        assertThat(instances.map { it[Instances.INSTANCE_START] }).containsExactly(null, null)
        assertThat(instances.map { it[Instances.INSTANCE_DUE] }).containsExactly(
            due.toEpochMilliseconds(), (due + 7.days).toEpochMilliseconds(),
        ).inOrder()
        assertThat(instances.map { it[Instances.INSTANCE_DURATION] }).containsExactly(null, null)
    }

    @Test
    fun `instance ids are unique across series and plain tasks`() {
        val start = Instant.parse("2026-10-09T09:00:00Z")
        val a = TaskEntity(id = 1, listId = 1, uid = "a", dtstart = start, rrule = "FREQ=DAILY;COUNT=4", timezone = "UTC")
        val b = TaskEntity(id = 2, listId = 1, uid = "b", dtstart = start, rrule = "FREQ=DAILY;COUNT=4", timezone = "UTC")
        val plain = TaskEntity(id = 3, listId = 2, uid = "c")

        val ids = build(listOf(a, b, plain)).rows.getValue(ProviderTable.INSTANCES).map { it[Tasks.ID] }

        assertThat(ids).hasSize(9)
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `an override is flagged all-day like the occurrence it replaces`() {
        val day = Instant.parse("2026-10-09T00:00:00Z")
        val master = TaskEntity(id = 1, listId = 1, uid = "s", due = day, isAllDay = true, rrule = "FREQ=DAILY;COUNT=2")
        val override = master.copy(id = 2, rrule = null, masterId = 1, recurrenceId = day + 1.days)

        val row = build(listOf(master, override)).rows.getValue(ProviderTable.TASKS).single { it[Tasks.ID] == 2L }

        assertThat(row[Tasks.ORIGINAL_INSTANCE_ALLDAY]).isEqualTo(1)
    }

    @Test
    fun `a timed date sorts as itself`() {
        val due = Instant.parse("2026-10-12T15:30:00Z")
        val task = TaskEntity(id = 1, listId = 1, uid = "u", due = due, dtstart = due - 1.hours)

        val instance = build(listOf(task)).rows.getValue(ProviderTable.INSTANCES).single()

        assertThat(instance[Instances.INSTANCE_DUE_SORTING]).isEqualTo(due.toEpochMilliseconds())
        assertThat(instance[Instances.INSTANCE_START_SORTING]).isEqualTo((due - 1.hours).toEpochMilliseconds())
    }

    @Test
    fun `an alarm before the start says so`() {
        val task = TaskEntity(id = 3, listId = 1, uid = "u")
        val alarm = TaskAlarmEntity(id = 1, taskId = 3, minutesBefore = 60, reference = AlarmReference.START, message = "Go")

        val row = build(listOf(task), listOf(alarm)).rows.getValue(ProviderTable.PROPERTIES).single()

        assertThat(row[Properties.TASK_ID]).isEqualTo(3L)
        assertThat(row[Alarm.REFERENCE]).isEqualTo(Alarm.REFERENCE_START.toString())
        assertThat(row[Alarm.MESSAGE]).isEqualTo("Go")
        assertThat(row[Alarm.ALARM_TYPE]).isEqualTo(Alarm.TYPE_MESSAGE.toString())
    }

    @Test
    fun `an instance keeps its id when other occurrences come and go`() {
        val start = Instant.parse("2026-10-09T09:00:00Z")
        val master = TaskEntity(id = 10, listId = 1, uid = "s", dtstart = start, rrule = "FREQ=DAILY;COUNT=4", timezone = "UTC")
        val plain = TaskEntity(id = 3, listId = 1, uid = "p")
        fun ids(vararg tasks: TaskEntity) = build(tasks.toList()).rows.getValue(ProviderTable.INSTANCES)
            .associate { it[Instances.INSTANCE_ORIGINAL_TIME] to it[Tasks.ID] }

        val before = ids(plain, master)
        val after = ids(master.copy(exdate = "20261010T090000Z"))

        val third = (start + 2.days).toEpochMilliseconds()
        assertThat(after[third]).isEqualTo(before[third])
        assertThat(after).hasSize(3)
    }

    @Test
    fun `an overridden occurrence keeps the id it had before`() {
        val start = Instant.parse("2026-10-09T09:00:00Z")
        val master = TaskEntity(id = 10, listId = 1, uid = "s", dtstart = start, rrule = "FREQ=DAILY;COUNT=3", timezone = "UTC")
        val override = master.copy(id = 11, rrule = null, masterId = 10, recurrenceId = start + 1.days, title = "x")
        fun idOfSecond(vararg tasks: TaskEntity) = build(tasks.toList()).rows.getValue(ProviderTable.INSTANCES)
            .single { it[Instances.INSTANCE_ORIGINAL_TIME] == (start + 1.days).toEpochMilliseconds() }[Tasks.ID]

        assertThat(idOfSecond(master, override)).isEqualTo(idOfSecond(master))
    }

    @Test
    fun `a property keeps its id when other properties come and go`() {
        val a = TaskEntity(id = 3, listId = 1, uid = "a", categories = listOf("one", "two"))
        val b = TaskEntity(id = 4, listId = 1, uid = "b", categories = listOf("three"))
        fun ids(tasks: List<TaskEntity>, alarms: List<TaskAlarmEntity>) = build(tasks, alarms)
            .rows.getValue(ProviderTable.PROPERTIES)
            .associate { (it[Category.NAME] ?: it[Alarm.MINUTES_BEFORE]) to it[Properties.PROPERTY_ID] }
        val first = TaskAlarmEntity(id = 7, taskId = 3, minutesBefore = 5)
        val second = TaskAlarmEntity(id = 8, taskId = 4, minutesBefore = 10)

        val before = ids(listOf(a, b), listOf(first, second))
        val after = ids(listOf(a.copy(categories = listOf("two")), b), listOf(second))

        assertThat(after["two"]).isEqualTo(before["two"])
        assertThat(after["three"]).isEqualTo(before["three"])
        assertThat(after["10"]).isEqualTo(before["10"])
    }
}

package com.aiexile.agendula.data.tasks.provider

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.data.tasks.ProviderEnvironment
import com.aiexile.agendula.data.tasks.ProviderResolver
import com.aiexile.agendula.data.tasks.StorageMode
import com.aiexile.agendula.data.tasks.TasksContract
import com.aiexile.agendula.data.tasks.TasksContract.Alarm
import com.aiexile.agendula.data.tasks.TasksContract.Category
import com.aiexile.agendula.data.tasks.TasksContract.Instances
import com.aiexile.agendula.data.tasks.TasksContract.Properties
import com.aiexile.agendula.data.tasks.TasksContract.Tasks
import com.aiexile.agendula.data.tasks.room.AccountEntity
import com.aiexile.agendula.data.tasks.room.AlarmReference
import com.aiexile.agendula.data.tasks.room.RoomTasksDataSource
import com.aiexile.agendula.data.tasks.room.TaskAlarmEntity
import com.aiexile.agendula.data.tasks.room.TaskEntity
import com.aiexile.agendula.data.tasks.room.TaskListEntity
import com.aiexile.agendula.data.tasks.room.TasksDatabase
import com.aiexile.agendula.domain.TaskStatus
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.awaitCancellation
import kotlin.time.Instant

/** Writes from another app, applied to the own store through the published tables. */
@RunWith(AndroidJUnit4::class)
class ProviderWriterTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: TasksDatabase
    private lateinit var resolver: ProviderResolver
    private lateinit var source: ProviderSnapshotSource
    private lateinit var writer: ProviderWriter
    private var afterWrites = 0
    private var home = 0L

    /** Truncated to the store's granularity, and on the hour so occurrences land on round times. */
    private val today = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() / 3_600_000 * 3_600_000)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, TasksDatabase::class.java).allowMainThreadQueries().build()
        resolver = ProviderResolver(NoProvider)
        source = ProviderSnapshotSource(context, db, resolver, awaitStartup = {})
        writer = ProviderWriter(db, RoomTasksDataSource(db), source, resolver, afterWrite = { afterWrites++ })
        home = db.taskLists().insert(TaskListEntity(name = "Home", color = 0))
    }

    @After
    fun tearDown() = db.close()

    private fun task(id: Long) = db.tasks().entity(id)

    private fun insert(vararg values: Pair<String, Any?>): Long =
        writer.insert(ProviderTable.TASKS, mapOf(Tasks.LIST_ID to home, *values))

    private fun byId(table: ProviderTable, id: Long) = "${table.idColumn} = $id"

    private fun synced(readOnly: Boolean = false): Long {
        val account = db.accounts().insert(AccountEntity(displayName = "me", username = "me"))
        return db.taskLists().insert(
            TaskListEntity(name = "Work", color = 0, accountId = account, href = "https://s/w/", isReadOnly = readOnly),
        )
    }

    private fun dailySeries(title: String = "Daily"): Long = db.tasks().insert(
        TaskEntity(listId = home, uid = title, title = title, dtstart = today - 1.days, due = today - 1.days + 1.hours,
            rrule = "FREQ=DAILY;COUNT=5", timezone = "UTC"),
    )

    /** Each instance of [taskId]'s series by its original time, with its task row and status. */
    private fun instances(): List<Triple<Long, Long, Int>> =
        source.rows(
            ProviderTable.INSTANCES,
            listOf(Instances.INSTANCE_ORIGINAL_TIME, Instances.TASK_ID, Tasks.STATUS),
            null,
            null,
        ).map { (original, taskId, status) -> Triple(original as Long, taskId as Long, (status as Long).toInt()) }

    private fun instanceId(original: Instant): Long =
        source.rows(
            ProviderTable.INSTANCES,
            listOf(Tasks.ID),
            "${Instances.INSTANCE_ORIGINAL_TIME} = ?",
            arrayOf(original.toEpochMilliseconds().toString()),
        ).single().single() as Long

    // --- inserts ----------------------------------------------------------------

    @Test
    fun anInsertedTaskIsPublishedAndOwedASync() {
        val id = insert(Tasks.TITLE to "Milk", Tasks.PRIORITY to 1)

        val stored = task(id)!!
        assertThat(stored.title).isEqualTo("Milk")
        assertThat(stored.priority).isEqualTo(1)
        assertThat(stored.isDirty).isTrue()
        assertThat(source.rows(ProviderTable.TASKS, listOf(Tasks.TITLE), byId(ProviderTable.TASKS, id), null))
            .containsExactly(listOf("Milk"))
        assertThat(afterWrites).isEqualTo(1)
    }

    @Test
    fun anInsertNeedsAListThatExistsAndIsWritable() {
        assertThrows(IllegalArgumentException::class.java) { writer.insert(ProviderTable.TASKS, mapOf(Tasks.TITLE to "x")) }
        assertThrows(IllegalArgumentException::class.java) { insert(Tasks.LIST_ID to 999L) }
        val readOnly = synced(readOnly = true)
        assertThrows(UnsupportedOperationException::class.java) { insert(Tasks.LIST_ID to readOnly) }
        assertThat(db.tasks().live()).isEmpty()
    }

    @Test
    fun anOccurrenceCannotBeInsertedAsATask() {
        val series = dailySeries()

        assertThrows(IllegalArgumentException::class.java) {
            insert(Tasks.ORIGINAL_INSTANCE_ID to series, Tasks.ORIGINAL_INSTANCE_TIME to today.toEpochMilliseconds())
        }
    }

    @Test
    fun aDuplicateUidIsRejected() {
        insert(Tasks.UID to "same")

        assertThrows(IllegalArgumentException::class.java) { insert(Tasks.UID to "same") }
    }

    @Test
    fun listsAndInstancesTakeNoInserts() {
        assertThrows(UnsupportedOperationException::class.java) {
            writer.insert(ProviderTable.LISTS, mapOf(TasksContract.Lists.NAME to "New"))
        }
        assertThrows(UnsupportedOperationException::class.java) { writer.insert(ProviderTable.INSTANCES, emptyMap()) }
        assertThrows(UnsupportedOperationException::class.java) {
            writer.update(ProviderTable.LISTS, mapOf(TasksContract.Lists.NAME to "x"), null, null)
        }
        assertThrows(UnsupportedOperationException::class.java) { writer.delete(ProviderTable.LISTS, null, null) }
    }

    @Test
    fun anUnknownColumnIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { insert("etag" to "x") }
    }

    @Test
    fun nothingIsWrittenInExternalMode() {
        val id = insert(Tasks.TITLE to "Mine")
        resolver.storageMode = StorageMode.EXTERNAL

        assertThrows(IllegalStateException::class.java) { insert() }
        assertThrows(IllegalStateException::class.java) {
            writer.update(ProviderTable.TASKS, mapOf(Tasks.TITLE to "x"), null, null)
        }
        assertThat(task(id)!!.title).isEqualTo("Mine")
    }

    // --- updates ----------------------------------------------------------------

    @Test
    fun anUpdateChangesTheRowsItsSelectionMatches() {
        val a = insert(Tasks.TITLE to "a")
        val b = insert(Tasks.TITLE to "b")

        val count = writer.update(ProviderTable.TASKS, mapOf(Tasks.STATUS to 2), "${Tasks.TITLE} = ?", arrayOf("b"))

        assertThat(count).isEqualTo(1)
        assertThat(task(a)!!.status).isEqualTo(TaskStatus.NEEDS_ACTION)
        assertThat(task(b)!!.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(task(b)!!.completedAt).isNotNull()
    }

    @Test
    fun anUpdateMatchingNothingChangesNothing() {
        insert(Tasks.TITLE to "a")

        assertThat(writer.update(ProviderTable.TASKS, mapOf(Tasks.TITLE to "x"), "${Tasks.TITLE} = 'zzz'", null)).isEqualTo(0)
    }

    @Test
    fun aTaskInAReadOnlyListCannotChange() {
        val readOnly = synced(readOnly = true)
        val id = db.tasks().insert(TaskEntity(listId = readOnly, uid = "r", title = "theirs"))

        assertThrows(UnsupportedOperationException::class.java) {
            writer.update(ProviderTable.TASKS, mapOf(Tasks.TITLE to "mine"), byId(ProviderTable.TASKS, id), null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            writer.delete(ProviderTable.TASKS, byId(ProviderTable.TASKS, id), null)
        }
        assertThat(task(id)!!.title).isEqualTo("theirs")
    }

    @Test
    fun aTaskMovesBetweenLists() {
        val work = synced()
        val id = insert(Tasks.TITLE to "t")

        writer.update(ProviderTable.TASKS, mapOf(Tasks.LIST_ID to work), byId(ProviderTable.TASKS, id), null)

        assertThat(db.tasks().live().single { it.title == "t" }.listId).isEqualTo(work)
    }

    @Test
    fun aParentMustBeInTheSameListAndNotADescendant() {
        val work = synced()
        val parent = insert(Tasks.TITLE to "parent")
        val child = insert(Tasks.TITLE to "child", Tasks.PARENT_ID to parent)
        val elsewhere = insert(Tasks.LIST_ID to work, Tasks.TITLE to "elsewhere")

        assertThat(task(child)!!.parentId).isEqualTo(parent)
        assertThrows(IllegalArgumentException::class.java) {
            writer.update(ProviderTable.TASKS, mapOf(Tasks.PARENT_ID to elsewhere), byId(ProviderTable.TASKS, child), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            writer.update(ProviderTable.TASKS, mapOf(Tasks.PARENT_ID to child), byId(ProviderTable.TASKS, parent), null)
        }
        assertThrows(IllegalArgumentException::class.java) { insert(Tasks.PARENT_ID to 999L) }
    }

    @Test
    fun movingASeriesDropsTheOverridesItNoLongerGenerates() {
        val series = dailySeries()
        writer.update(ProviderTable.INSTANCES, mapOf(Tasks.STATUS to 2), byId(ProviderTable.INSTANCES, instanceId(today)), null)
        assertThat(db.tasks().overrides(series)).hasSize(1)

        writer.update(
            ProviderTable.TASKS,
            mapOf(Tasks.DTSTART to (today + 2.hours).toEpochMilliseconds(), Tasks.DUE to (today + 3.hours).toEpochMilliseconds()),
            byId(ProviderTable.TASKS, series),
            null,
        )

        assertThat(db.tasks().overrides(series)).isEmpty()
    }

    @Test
    fun aClientCannotTouchSyncState() {
        val work = synced()
        val id = db.tasks().insert(
            TaskEntity(listId = work, uid = "s", title = "t", href = "/s.ics", etag = "e", sequence = 2, unknownProperties = "X-A:1"),
        )

        writer.update(ProviderTable.TASKS, mapOf(Tasks.TITLE to "new", "_sync_id" to "x", "sync1" to "y"), byId(ProviderTable.TASKS, id), null)

        val stored = task(id)!!
        assertThat(stored.title).isEqualTo("new")
        assertThat(stored.href).isEqualTo("/s.ics")
        assertThat(stored.etag).isEqualTo("e")
        assertThat(stored.sequence).isEqualTo(2)
        assertThat(stored.unknownProperties).isEqualTo("X-A:1")
        assertThat(stored.isDirty).isTrue()
    }

    // --- instances --------------------------------------------------------------

    @Test
    fun completingAnOccurrenceTicksOffOnlyThatOne() {
        val series = dailySeries()

        val count = writer.update(ProviderTable.INSTANCES, mapOf(Tasks.STATUS to 2), byId(ProviderTable.INSTANCES, instanceId(today)), null)

        assertThat(count).isEqualTo(1)
        assertThat(task(series)!!.status).isEqualTo(TaskStatus.NEEDS_ACTION)
        val override = db.tasks().overrides(series).single()
        assertThat(override.recurrenceId).isEqualTo(today)
        assertThat(override.status).isEqualTo(TaskStatus.COMPLETED)
        val statuses = instances().associate { it.first to it.third }
        assertThat(statuses[today.toEpochMilliseconds()]).isEqualTo(TasksContract.STATUS_COMPLETED)
        assertThat(statuses.filterKeys { it != today.toEpochMilliseconds() }.values.toSet())
            .containsExactly(TasksContract.STATUS_NEEDS_ACTION)
    }

    @Test
    fun anOccurrenceCanBeCancelledAndReopened() {
        val series = dailySeries()

        writer.update(ProviderTable.INSTANCES, mapOf(Tasks.STATUS to 3), byId(ProviderTable.INSTANCES, instanceId(today)), null)
        assertThat(db.tasks().overrides(series).single().status).isEqualTo(TaskStatus.CANCELLED)

        writer.update(ProviderTable.INSTANCES, mapOf(Tasks.PERCENT_COMPLETE to 0), byId(ProviderTable.INSTANCES, instanceId(today)), null)
        assertThat(db.tasks().overrides(series).single().status).isEqualTo(TaskStatus.NEEDS_ACTION)
    }

    @Test
    fun contentWrittenToAnOccurrenceEditsTheSeries() {
        val series = dailySeries()

        writer.update(ProviderTable.INSTANCES, mapOf(Tasks.TITLE to "Renamed"), byId(ProviderTable.INSTANCES, instanceId(today)), null)

        assertThat(task(series)!!.title).isEqualTo("Renamed")
        assertThat(db.tasks().overrides(series)).isEmpty()
    }

    @Test
    fun anOccurrenceCannotBeMoved() {
        dailySeries()

        assertThrows(IllegalArgumentException::class.java) {
            writer.update(ProviderTable.INSTANCES, mapOf(Tasks.DUE to 0L), byId(ProviderTable.INSTANCES, instanceId(today)), null)
        }
    }

    @Test
    fun anOccurrenceHasNoPartialProgress() {
        dailySeries()

        assertThrows(IllegalArgumentException::class.java) {
            writer.update(ProviderTable.INSTANCES, mapOf(Tasks.PERCENT_COMPLETE to 50), byId(ProviderTable.INSTANCES, instanceId(today)), null)
        }
    }

    @Test
    fun aPlainTaskInstanceWritesTheTask() {
        val id = insert(Tasks.TITLE to "t", Tasks.DUE to today.toEpochMilliseconds())
        val instance = source.rows(ProviderTable.INSTANCES, listOf(Tasks.ID), "${Instances.TASK_ID} = $id", null).single().single() as Long

        writer.update(ProviderTable.INSTANCES, mapOf(Tasks.PERCENT_COMPLETE to 50, Tasks.DUE to (today + 1.days).toEpochMilliseconds()), byId(ProviderTable.INSTANCES, instance), null)

        assertThat(task(id)!!.status).isEqualTo(TaskStatus.IN_PROCESS)
        assertThat(task(id)!!.due).isEqualTo(today + 1.days)
    }

    @Test
    fun deletingAnOccurrenceExceptsIt() {
        val series = dailySeries()

        assertThat(writer.delete(ProviderTable.INSTANCES, byId(ProviderTable.INSTANCES, instanceId(today)), null)).isEqualTo(1)

        assertThat(task(series)!!.exdate).isNotNull()
        assertThat(instances().map { it.first }).doesNotContain(today.toEpochMilliseconds())
        assertThat(instances()).hasSize(4)
    }

    @Test
    fun deletingAModifiedOccurrenceExceptsItToo() {
        val series = dailySeries()
        writer.update(ProviderTable.INSTANCES, mapOf(Tasks.STATUS to 2), byId(ProviderTable.INSTANCES, instanceId(today)), null)

        writer.delete(ProviderTable.INSTANCES, byId(ProviderTable.INSTANCES, instanceId(today)), null)

        assertThat(db.tasks().overrides(series)).isEmpty()
        assertThat(instances().map { it.first }).doesNotContain(today.toEpochMilliseconds())
    }

    // --- deletes ----------------------------------------------------------------

    @Test
    fun aParentGoesWithItsSubtasks() {
        val parent = insert(Tasks.TITLE to "parent")
        val child = insert(Tasks.TITLE to "child", Tasks.PARENT_ID to parent)
        val grandchild = insert(Tasks.TITLE to "grandchild", Tasks.PARENT_ID to child)
        val other = insert(Tasks.TITLE to "other")

        assertThat(writer.delete(ProviderTable.TASKS, byId(ProviderTable.TASKS, parent), null)).isEqualTo(1)

        assertThat(task(parent)).isNull()
        assertThat(task(child)).isNull()
        assertThat(task(grandchild)).isNull()
        assertThat(task(other)).isNotNull()
    }

    @Test
    fun aSyncedTaskLeavesATombstone() {
        val work = synced()
        val id = insert(Tasks.LIST_ID to work, Tasks.TITLE to "t")

        writer.delete(ProviderTable.TASKS, byId(ProviderTable.TASKS, id), null)

        assertThat(task(id)!!.isDeleted).isTrue()
        assertThat(source.rows(ProviderTable.TASKS, listOf(Tasks.ID), null, null)).isEmpty()
        assertThat(writer.update(ProviderTable.TASKS, mapOf(Tasks.TITLE to "x"), byId(ProviderTable.TASKS, id), null)).isEqualTo(0)
    }

    @Test
    fun deletingAnOverrideThroughTasksExceptsTheOccurrence() {
        val series = dailySeries()
        writer.update(ProviderTable.INSTANCES, mapOf(Tasks.STATUS to 2), byId(ProviderTable.INSTANCES, instanceId(today)), null)
        val override = db.tasks().overrides(series).single().id

        writer.delete(ProviderTable.TASKS, byId(ProviderTable.TASKS, override), null)

        assertThat(task(series)).isNotNull()
        assertThat(task(series)!!.exdate).isNotNull()
        assertThat(instances()).hasSize(4)
    }

    // --- properties -------------------------------------------------------------

    @Test
    fun anAlarmCanBeAddedChangedAndRemoved() {
        val id = insert(Tasks.TITLE to "t", Tasks.DUE to (today + 1.days).toEpochMilliseconds())
        db.tasks().update(task(id)!!.copy(isDirty = false))

        val propertyId = writer.insert(
            ProviderTable.PROPERTIES,
            mapOf(Properties.TASK_ID to id, Properties.MIMETYPE to Alarm.MIMETYPE, Alarm.MINUTES_BEFORE to "15", Alarm.REFERENCE to "1"),
        )
        val alarm = db.alarms().forTask(id).single()
        assertThat(alarm.minutesBefore).isEqualTo(15)
        assertThat(alarm.reference).isEqualTo(AlarmReference.DUE)
        assertThat(task(id)!!.isDirty).isTrue()
        assertThat(propertyId).isEqualTo(ProviderIds.alarm(alarm.id))

        writer.update(ProviderTable.PROPERTIES, mapOf(Alarm.MINUTES_BEFORE to 60, Alarm.REFERENCE to 2), byId(ProviderTable.PROPERTIES, propertyId), null)
        assertThat(db.alarms().forTask(id).single().minutesBefore).isEqualTo(60)
        assertThat(db.alarms().forTask(id).single().reference).isEqualTo(AlarmReference.START)

        writer.delete(ProviderTable.PROPERTIES, "${Properties.MIMETYPE} = ?", arrayOf(Alarm.MIMETYPE))
        assertThat(db.alarms().forTask(id)).isEmpty()
    }

    @Test
    fun tagsCanBeAddedRenamedAndRemoved() {
        val id = insert(Tasks.TITLE to "t")

        val propertyId = writer.insert(
            ProviderTable.PROPERTIES,
            mapOf(Properties.TASK_ID to id, Properties.MIMETYPE to Category.MIMETYPE, Category.NAME to " errand "),
        )
        assertThat(task(id)!!.categories).containsExactly("errand")

        writer.update(ProviderTable.PROPERTIES, mapOf(Category.NAME to "shopping"), byId(ProviderTable.PROPERTIES, propertyId), null)
        assertThat(task(id)!!.categories).containsExactly("shopping")

        writer.insert(ProviderTable.PROPERTIES, mapOf(Properties.TASK_ID to id, Properties.MIMETYPE to Category.MIMETYPE, Category.NAME to "shopping"))
        assertThat(task(id)!!.categories).containsExactly("shopping")

        writer.delete(ProviderTable.PROPERTIES, "${Category.NAME} = ?", arrayOf("shopping"))
        assertThat(task(id)!!.categories).isEmpty()
    }

    @Test
    fun aPropertyCannotChangeItsKindOrTask() {
        val id = insert(Tasks.TITLE to "t")
        val other = insert(Tasks.TITLE to "o")
        db.alarms().insert(TaskAlarmEntity(taskId = id, minutesBefore = 5))
        val propertyId = source.rows(ProviderTable.PROPERTIES, listOf(Properties.PROPERTY_ID), null, null).single().single() as Long

        assertThrows(IllegalArgumentException::class.java) {
            writer.update(ProviderTable.PROPERTIES, mapOf(Properties.MIMETYPE to Category.MIMETYPE), byId(ProviderTable.PROPERTIES, propertyId), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            writer.update(ProviderTable.PROPERTIES, mapOf(Properties.TASK_ID to other), byId(ProviderTable.PROPERTIES, propertyId), null)
        }
    }

    @Test
    fun onlyAlarmsAndTagsCanBeWritten() {
        val id = insert(Tasks.TITLE to "t")

        assertThrows(IllegalArgumentException::class.java) {
            writer.insert(ProviderTable.PROPERTIES, mapOf(Properties.TASK_ID to id, Properties.MIMETYPE to "vnd.android.cursor.item/comment"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            writer.insert(ProviderTable.PROPERTIES, mapOf(Properties.TASK_ID to id, Properties.MIMETYPE to Alarm.MIMETYPE))
        }
    }

    // --- batches ----------------------------------------------------------------

    @Test
    fun aBatchIsAllOrNothing() {
        assertThrows(IllegalArgumentException::class.java) {
            writer.batch {
                insert(Tasks.TITLE to "first")
                insert(Tasks.TITLE to "second")
                insert(Tasks.LIST_ID to 999L)
            }
        }

        assertThat(db.tasks().live()).isEmpty()
        assertThat(source.rows(ProviderTable.TASKS, listOf(Tasks.ID), null, null)).isEmpty()
    }

    @Test
    fun aBatchSeesItsOwnWritesAndReArmsRemindersOnce() {
        writer.batch {
            insert(Tasks.TITLE to "first")
            writer.update(ProviderTable.TASKS, mapOf(Tasks.STATUS to 2), "${Tasks.TITLE} = ?", arrayOf("first"))
        }

        assertThat(db.tasks().live().single().status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(afterWrites).isEqualTo(1)
    }

    // --- review regressions -----------------------------------------------------

    @Test
    fun aBatchDeletingTwoPropertiesDeletesThoseTwo() {
        val a = insert(Tasks.TITLE to "a")
        val b = insert(Tasks.TITLE to "b")
        listOf(5, 10).forEach { db.alarms().insert(TaskAlarmEntity(taskId = a, minutesBefore = it)) }
        db.alarms().insert(TaskAlarmEntity(taskId = b, minutesBefore = 15))
        val ids = source.rows(ProviderTable.PROPERTIES, listOf(Properties.PROPERTY_ID), "${Properties.TASK_ID} = $a", null)
            .map { it.single() as Long }

        writer.batch { ids.forEach { writer.delete(ProviderTable.PROPERTIES, byId(ProviderTable.PROPERTIES, it), null) } }

        assertThat(db.alarms().forTask(a)).isEmpty()
        assertThat(db.alarms().forTask(b).map { it.minutesBefore }).containsExactly(15)
    }

    @Test
    fun aBatchDeletingTwoOccurrencesDeletesThoseTwo() {
        dailySeries()
        val first = instanceId(today)
        val second = instanceId(today + 1.days)

        writer.batch {
            writer.delete(ProviderTable.INSTANCES, byId(ProviderTable.INSTANCES, first), null)
            writer.delete(ProviderTable.INSTANCES, byId(ProviderTable.INSTANCES, second), null)
        }

        assertThat(instances().map { it.first }).containsExactly(
            (today - 1.days).toEpochMilliseconds(),
            (today + 2.days).toEpochMilliseconds(),
            (today + 3.days).toEpochMilliseconds(),
        )
    }

    @Test
    fun anInstanceIdOutlivesAnotherOccurrencesDeletion() {
        dailySeries()
        val later = instanceId(today + 2.days)

        writer.delete(ProviderTable.INSTANCES, byId(ProviderTable.INSTANCES, instanceId(today)), null)

        assertThat(instanceId(today + 2.days)).isEqualTo(later)
        assertThat(writer.update(ProviderTable.INSTANCES, mapOf(Tasks.STATUS to 2), byId(ProviderTable.INSTANCES, later), null))
            .isEqualTo(1)
        assertThat(instances().single { it.third == TasksContract.STATUS_COMPLETED }.first)
            .isEqualTo((today + 2.days).toEpochMilliseconds())
    }

    @Test
    fun aRecurringSubtaskOutsideTheWindowGoesWithItsParent() {
        val parent = insert(Tasks.TITLE to "parent")
        val longAgo = today - 1000.days
        val subtask = db.tasks().insert(
            TaskEntity(listId = home, uid = "old", title = "old", dtstart = longAgo, rrule = "FREQ=DAILY;COUNT=2",
                timezone = "UTC", parentId = parent),
        )

        writer.delete(ProviderTable.TASKS, byId(ProviderTable.TASKS, parent), null)

        assertThat(task(subtask)).isNull()
    }

    @Test
    fun anOverriddenRecurringSubtaskGoesWholeWithItsParent() {
        val parent = insert(Tasks.TITLE to "parent")
        val subtask = dailySeries("sub")
        db.tasks().update(task(subtask)!!.copy(parentId = parent))
        writer.update(ProviderTable.INSTANCES, mapOf(Tasks.STATUS to 2), byId(ProviderTable.INSTANCES, instanceId(today)), null)

        writer.delete(ProviderTable.TASKS, byId(ProviderTable.TASKS, parent), null)

        assertThat(task(subtask)).isNull()
        assertThat(db.tasks().live()).isEmpty()
    }

    @Test
    fun aModifiedOccurrenceCanBeWrittenBackAsRead() {
        val series = dailySeries()
        writer.update(ProviderTable.INSTANCES, mapOf(Tasks.STATUS to 2), byId(ProviderTable.INSTANCES, instanceId(today)), null)
        val override = db.tasks().overrides(series).single().id
        val row = readRow(ProviderTable.TASKS, override)

        writer.update(ProviderTable.TASKS, row + (Tasks.TITLE to "Renamed"), byId(ProviderTable.TASKS, override), null)

        assertThat(task(override)!!.title).isEqualTo("Renamed")
    }

    @Test
    fun anInstanceCanBeWrittenBackAsReadToCompleteIt() {
        val series = dailySeries()
        val instance = instanceId(today)
        val row = readRow(ProviderTable.INSTANCES, instance)

        writer.update(ProviderTable.INSTANCES, row + (Tasks.STATUS to 2), byId(ProviderTable.INSTANCES, instance), null)

        assertThat(db.tasks().overrides(series).single().status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(task(series)!!.dtstart).isEqualTo(today - 1.days)
    }

    @Test
    fun movingASubtreeMovesItsSubtasks() {
        val work = synced()
        val parent = insert(Tasks.TITLE to "parent")
        val child = insert(Tasks.TITLE to "child", Tasks.PARENT_ID to parent)
        val grandchild = insert(Tasks.TITLE to "grandchild", Tasks.PARENT_ID to child)

        writer.update(ProviderTable.TASKS, mapOf(Tasks.LIST_ID to work), byId(ProviderTable.TASKS, parent), null)

        assertThat(listOf(parent, child, grandchild).map { task(it)!!.listId }).containsExactly(work, work, work)
        assertThat(task(child)!!.parentId).isEqualTo(parent)
        assertThat(task(grandchild)!!.parentId).isEqualTo(child)
    }

    @Test
    fun aMoveOntoATakenUidIsRejected() {
        val work = synced()
        insert(Tasks.LIST_ID to work, Tasks.UID to "same")
        val id = insert(Tasks.UID to "same")

        assertThrows(IllegalArgumentException::class.java) {
            writer.update(ProviderTable.TASKS, mapOf(Tasks.LIST_ID to work), byId(ProviderTable.TASKS, id), null)
        }
        assertThat(task(id)!!.listId).isEqualTo(home)
    }

    @Test
    fun contentOnAModifiedOccurrenceStillEditsTheSeries() {
        val series = dailySeries()
        writer.update(ProviderTable.INSTANCES, mapOf(Tasks.STATUS to 2), byId(ProviderTable.INSTANCES, instanceId(today)), null)
        val override = db.tasks().overrides(series).single().id

        writer.update(ProviderTable.INSTANCES, mapOf(Tasks.TITLE to "Renamed"), byId(ProviderTable.INSTANCES, instanceId(today)), null)

        assertThat(task(series)!!.title).isEqualTo("Renamed")
        assertThat(task(override)!!.title).isEqualTo("Renamed")
        assertThat(task(override)!!.status).isEqualTo(TaskStatus.COMPLETED)
    }

    @Test
    fun aWriteThatChangesNothingOwesNoSync() {
        val id = insert(Tasks.TITLE to "t")
        db.tasks().update(task(id)!!.copy(isDirty = false))
        val modified = task(id)!!.lastModified

        writer.update(ProviderTable.TASKS, readRow(ProviderTable.TASKS, id), byId(ProviderTable.TASKS, id), null)
        writer.update(ProviderTable.TASKS, emptyMap(), byId(ProviderTable.TASKS, id), null)

        assertThat(task(id)!!.isDirty).isFalse()
        assertThat(task(id)!!.lastModified).isEqualTo(modified)
    }

    @Test
    fun reopeningAnOpenOccurrenceForksNothing() {
        val series = dailySeries()

        writer.update(ProviderTable.INSTANCES, mapOf(Tasks.STATUS to 0), byId(ProviderTable.INSTANCES, instanceId(today)), null)

        assertThat(db.tasks().overrides(series)).isEmpty()
    }

    @Test
    fun aWriteBeforeStartupSettlesIsRefused() {
        val stuck = ProviderSnapshotSource(context, db, resolver, awaitStartup = { awaitCancellation() }, startupWait = 50.milliseconds)
        val early = ProviderWriter(db, RoomTasksDataSource(db), stuck, resolver, afterWrite = {})

        assertThrows(IllegalStateException::class.java) { early.insert(ProviderTable.TASKS, mapOf(Tasks.LIST_ID to home)) }
        assertThat(db.tasks().live()).isEmpty()
    }

    @Test
    fun aSingleRowWriteNeedsNoSnapshot() {
        val id = insert(Tasks.TITLE to "t")
        val unknown = 987_654L

        assertThat(writer.update(ProviderTable.TASKS, mapOf(Tasks.TITLE to "x"), byId(ProviderTable.TASKS, unknown), null)).isEqualTo(0)
        assertThat(writer.update(ProviderTable.TASKS, mapOf(Tasks.TITLE to "x"), byId(ProviderTable.TASKS, id), null)).isEqualTo(1)
        assertThat(writer.update(ProviderTable.INSTANCES, mapOf(Tasks.TITLE to "x"), byId(ProviderTable.INSTANCES, unknown), null)).isEqualTo(0)
        assertThat(writer.delete(ProviderTable.PROPERTIES, byId(ProviderTable.PROPERTIES, unknown), null)).isEqualTo(0)
    }

    // --- second review regressions -----------------------------------------------

    @Test
    fun tickingOffAModifiedOccurrenceAsReadLeavesTheSeriesAlone() {
        val series = dailySeries("Standup")
        // Reopening forks an override, which then gets its own title as the app's "this occurrence" edit would.
        store().setCompletedInstance(series, today, false)
        val override = db.tasks().overrides(series).single()
        db.tasks().update(override.copy(title = "Standup (moved room)"))
        val row = readRow(ProviderTable.INSTANCES, instanceId(today))

        writer.update(ProviderTable.INSTANCES, row + (Tasks.STATUS to 2), byId(ProviderTable.INSTANCES, instanceId(today)), null)

        assertThat(task(series)!!.title).isEqualTo("Standup")
        assertThat(task(override.id)!!.title).isEqualTo("Standup (moved room)")
        assertThat(task(override.id)!!.status).isEqualTo(TaskStatus.COMPLETED)
    }

    @Test
    fun anInstanceWrittenBackWithOnlyProgressChangedIsCompleted() {
        val series = dailySeries()
        val row = readRow(ProviderTable.INSTANCES, instanceId(today))

        writer.update(ProviderTable.INSTANCES, row + (Tasks.PERCENT_COMPLETE to 100), byId(ProviderTable.INSTANCES, instanceId(today)), null)

        assertThat(db.tasks().overrides(series).single().status).isEqualTo(TaskStatus.COMPLETED)
    }

    @Test
    fun aBadZoneOnAnInstanceReportsTheZone() {
        dailySeries()
        val instance = instanceId(today)

        val error = assertThrows(IllegalArgumentException::class.java) {
            writer.update(ProviderTable.INSTANCES, mapOf(Tasks.TZ to "Europe/Berln"), byId(ProviderTable.INSTANCES, instance), null)
        }

        assertThat(error).hasMessageThat().contains("Unknown time zone")
    }

    @Test
    fun anInProcessSeriesCanBeWrittenBackAsRead() {
        val series = dailySeries()
        db.tasks().update(task(series)!!.copy(status = TaskStatus.IN_PROCESS, percentComplete = 50))
        val row = readRow(ProviderTable.INSTANCES, instanceId(today))

        writer.update(ProviderTable.INSTANCES, row + (Tasks.TITLE to "Renamed"), byId(ProviderTable.INSTANCES, instanceId(today)), null)

        assertThat(task(series)!!.title).isEqualTo("Renamed")
        assertThat(task(series)!!.status).isEqualTo(TaskStatus.IN_PROCESS)
        assertThat(db.tasks().overrides(series)).isEmpty()
    }

    @Test
    fun aFloatingSeriesCanBeWrittenBackAsRead() {
        val series = db.tasks().insert(
            TaskEntity(listId = home, uid = "f", title = "Floating", due = today, rrule = "FREQ=DAILY;COUNT=3", timezone = null),
        )
        val row = readRow(ProviderTable.INSTANCES, instanceId(today))

        writer.update(ProviderTable.INSTANCES, row + (Tasks.STATUS to 2), byId(ProviderTable.INSTANCES, instanceId(today)), null)
        writer.update(ProviderTable.TASKS, mapOf(Tasks.TITLE to "Still floating"), byId(ProviderTable.TASKS, series), null)

        assertThat(db.tasks().overrides(series).single().status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(task(series)!!.timezone).isNull()
    }

    @Test
    fun aParentLoopDeletesInsteadOfRecursingForever() {
        val a = insert(Tasks.TITLE to "a")
        val b = insert(Tasks.TITLE to "b", Tasks.PARENT_ID to a)
        db.tasks().update(task(a)!!.copy(parentId = b))

        writer.delete(ProviderTable.TASKS, byId(ProviderTable.TASKS, a), null)

        assertThat(task(a)).isNull()
        assertThat(task(b)).isNull()
    }

    @Test
    fun aBulkMoveWorksWhicheverOrderTheRowsComeIn() {
        val work = synced()
        // The subtask gets the lower id, so it comes first unless the writer reorders.
        val child = insert(Tasks.TITLE to "child")
        val parent = insert(Tasks.TITLE to "parent")
        db.tasks().update(task(child)!!.copy(parentId = parent))

        val count = writer.update(ProviderTable.TASKS, mapOf(Tasks.LIST_ID to work), "${Tasks.LIST_ID} = ?", arrayOf(home.toString()))

        assertThat(count).isEqualTo(2)
        assertThat(task(child)!!.listId).isEqualTo(work)
        assertThat(task(parent)!!.listId).isEqualTo(work)
        assertThat(task(child)!!.parentId).isEqualTo(parent)
    }

    @Test
    fun aRowSelectedWithAnArgumentIsWritten() {
        val a = insert(Tasks.TITLE to "a")
        val b = insert(Tasks.TITLE to "b")

        assertThat(writer.update(ProviderTable.TASKS, mapOf(Tasks.TITLE to "x"), "${Tasks.ID}=?", arrayOf(b.toString()))).isEqualTo(1)
        assertThat(writer.update(ProviderTable.TASKS, mapOf(Tasks.TITLE to "x"), "${Tasks.ID} = ?", arrayOf("987654"))).isEqualTo(0)

        assertThat(task(a)!!.title).isEqualTo("a")
        assertThat(task(b)!!.title).isEqualTo("x")
    }

    @Test
    fun garbageExceptionDatesAreRejected() {
        val series = dailySeries()

        assertThrows(IllegalArgumentException::class.java) {
            writer.update(ProviderTable.TASKS, mapOf(Tasks.EXDATE to "tomorrow"), byId(ProviderTable.TASKS, series), null)
        }
        assertThat(task(series)!!.exdate).isNull()
    }

    private fun store() = RoomTasksDataSource(db)

    /** Every column of one published row, as a client that read it would write it back. */
    private fun readRow(table: ProviderTable, id: Long): Map<String, Any?> {
        val columns = table.columns.map { it.first }
        val row = source.rows(table, columns, byId(table, id), null).single()
        return columns.zip(row).toMap() - table.idColumn
    }

}

private object NoProvider : ProviderEnvironment {
    override fun packageDeclaring(authority: String): String? = null
    override fun isGranted(permission: String): Boolean = false
    override fun appLabel(packageName: String): String? = null
}

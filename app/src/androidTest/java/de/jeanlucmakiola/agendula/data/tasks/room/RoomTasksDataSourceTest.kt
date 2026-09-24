package de.jeanlucmakiola.agendula.data.tasks.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.data.tasks.TaskQuery
import de.jeanlucmakiola.agendula.data.tasks.TaskReminder
import de.jeanlucmakiola.agendula.domain.TaskForm
import de.jeanlucmakiola.agendula.domain.TaskStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The seam over Room, exercised through [de.jeanlucmakiola.agendula.data.tasks
 * .TasksDataSource] rather than the DAOs — recurrence expansion and override
 * forking only exist at this level.
 */
@RunWith(AndroidJUnit4::class)
class RoomTasksDataSourceTest {

    private lateinit var db: TasksDatabase
    private lateinit var source: RoomTasksDataSource
    private var listId = 0L

    /** Truncated to the store's granularity: instants are columns of epoch millis. */
    private val now get() = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TasksDatabase::class.java,
        ).allowMainThreadQueries().build()
        source = RoomTasksDataSource(db)
        listId = source.createLocalList("Personal", 0xFF112233.toInt())
    }

    @After
    fun tearDown() = db.close()

    private fun form(
        title: String = "task",
        due: Instant? = null,
        percentComplete: Int? = null,
    ) = TaskForm(title = title, listId = listId, due = due, percentComplete = percentComplete)

    /** A list that belongs to an account, so writes owe a server something. */
    private fun syncedList(): Long {
        val accountId = db.accounts().insert(
            AccountEntity(displayName = "me@example.com", username = "me"),
        )
        return db.taskLists().insert(
            TaskListEntity(name = "Work", color = 0, accountId = accountId, href = "https://s/w/"),
        )
    }

    /** Turns [taskId] into a weekly series anchored at [anchor]. */
    private fun makeRecurring(taskId: Long, anchor: Instant, rule: String = "FREQ=WEEKLY") {
        val entity = db.tasks().entity(taskId)!!
        db.tasks().update(entity.copy(dtstart = anchor, due = anchor + 1.days, rrule = rule))
    }

    @Test
    fun aWriteNamesTheListsItTouched() {
        val touched = mutableListOf<Set<Long>>()
        val observed = RoomTasksDataSource(db) { touched += it }
        val work = syncedList()
        val id = observed.insertTask(form().copy(listId = work))

        observed.updateTask(id, form().copy(listId = listId))

        assertThat(touched).containsExactly(setOf(work), setOf(work, listId)).inOrder()
    }

    @Test
    fun movingASyncedTaskLeavesATombstoneInTheOldList() {
        val work = syncedList()
        val id = source.insertTask(form().copy(listId = work))
        db.tasks().markSynced(listOf(id), "https://s/w/a.ics", "e1")

        source.updateTask(id, form().copy(listId = listId))

        val moved = db.tasks().entity(id)!!
        assertThat(moved.listId).isEqualTo(listId)
        assertThat(moved.href).isNull()
        val tombstone = db.tasks().allIn(work).single()
        assertThat(tombstone.isDeleted).isTrue()
        assertThat(tombstone.href).isEqualTo("https://s/w/a.ics")
        assertThat(tombstone.etag).isEqualTo("e1")
    }

    @Test
    fun createsAndReadsBackALocalList() {
        val lists = source.taskLists()

        assertThat(lists).hasSize(1)
        assertThat(lists.single().name).isEqualTo("Personal")
        // No account, so the list still has to report something the lists screen
        // can group under.
        assertThat(lists.single().isLocal).isTrue()
        assertThat(lists.single().accountName).isEqualTo("Local")
    }

    @Test
    fun renamesAndRecoloursAList() {
        source.updateList(listId, "  Errands  ", 0xFF445566.toInt())

        val list = source.taskLists().single()
        assertThat(list.name).isEqualTo("Errands")
        assertThat(list.color).isEqualTo(0xFF445566.toInt())
        // Nothing to sync a device-only list to, so the edit leaves it clean.
        assertThat(db.taskLists().entity(listId)!!.isDirty).isFalse()
    }

    @Test
    fun deletingAListTakesItsTasksWithIt() {
        source.insertTask(form(title = "Buy milk"))
        source.insertTask(form(title = "Call the bank"))
        val other = source.createLocalList("Work", 0xFF778899.toInt())
        val keeper = source.insertTask(TaskForm(title = "Ship it", listId = other))

        source.deleteList(listId)

        assertThat(source.taskLists().map { it.id }).containsExactly(other)
        assertThat(source.tasks(TaskQuery(includeCompleted = true)).map { it.taskId })
            .containsExactly(keeper)
    }

    @Test
    fun createsAndReadsBackANonRecurringTask() {
        val due = now + 1.days
        val id = source.insertTask(form(title = "Buy milk", due = due))

        val task = source.task(id)!!

        assertThat(task.taskId).isEqualTo(id)
        assertThat(task.title).isEqualTo("Buy milk")
        assertThat(task.due).isEqualTo(due)
        assertThat(task.isRecurring).isFalse()
        // A task that does not recur has no occurrence anchor, so it keys and edits
        // by task id exactly as it did against the provider.
        assertThat(task.occurrenceStart).isNull()
        assertThat(task.occurrenceKey).isEqualTo("$id")
    }

    @Test
    fun mintsAUidForEveryTask() {
        val id = source.insertTask(form())

        assertThat(db.tasks().entity(id)!!.uid).isNotEmpty()
    }

    @Test
    fun expandsARecurringSeriesIntoManyOccurrences() {
        val anchor = now
        val id = source.insertTask(form(title = "Water the plants"))
        makeRecurring(id, anchor)

        val occurrences = source.tasks(TaskQuery(listId = listId)).filter { it.taskId == id }

        // The provider materialised exactly one upcoming occurrence; we expand the
        // whole window, so a weekly series yields well over a hundred.
        assertThat(occurrences.size).isGreaterThan(100)
        assertThat(occurrences.map { it.occurrenceStart }).containsNoDuplicates()
        assertThat(occurrences.map { it.occurrenceKey }).containsNoDuplicates()
        assertThat(occurrences.all { it.isRecurring }).isTrue()
        // Each occurrence keeps the series' length rather than the master's dates.
        val first = occurrences.minBy { it.occurrenceStart!! }
        assertThat(first.due!! - first.start!!).isEqualTo(1.days)
    }

    @Test
    fun exactlyOneOccurrenceIsTheCurrentOne() {
        val id = source.insertTask(form())
        makeRecurring(id, now - 30.days)

        val occurrences = source.tasks(TaskQuery(listId = listId)).filter { it.taskId == id }

        assertThat(occurrences.count { it.distanceFromCurrent == 0 }).isEqualTo(1)
        assertThat(source.task(id)!!.distanceFromCurrent).isEqualTo(0)
    }

    @Test
    fun editingOneOccurrenceForksARecurrenceIdOverride() {
        val anchor = now
        val id = source.insertTask(form(title = "Water the plants"))
        makeRecurring(id, anchor)
        val target = source.tasks(TaskQuery(listId = listId))
            .filter { it.taskId == id }
            .first { it.distanceFromCurrent == 1 }

        source.updateInstance(id, target.occurrenceStart!!, form(title = "Water them twice"))

        val override = db.tasks().override(id, target.occurrenceStart)!!
        // RFC 5545's model: the override shares its master's UID — that is what
        // makes it an override rather than a separate task. The dmfs provider
        // detached the occurrence into a new task with its own UID instead.
        assertThat(override.uid).isEqualTo(db.tasks().entity(id)!!.uid)
        assertThat(override.masterId).isEqualTo(id)
        assertThat(override.recurrenceId).isEqualTo(target.occurrenceStart)
        assertThat(override.rrule).isNull()
        assertThat(override.title).isEqualTo("Water them twice")
    }

    @Test
    fun completingOneOccurrenceLeavesTheRestOfTheSeriesOpen() {
        val id = source.insertTask(form(title = "Water the plants"))
        makeRecurring(id, now)
        val open = { source.tasks(TaskQuery(listId = listId)).filter { it.taskId == id } }
        val before = open()
        val target = before.first { it.distanceFromCurrent == 0 }

        source.setCompletedInstance(id, target.occurrenceStart!!, completed = true)

        // Writing the status onto the master would close the series: the master is
        // the row the task query filters on, so every occurrence would vanish.
        val after = open()
        assertThat(after).hasSize(before.size - 1)
        assertThat(after.map { it.occurrenceStart }).doesNotContain(target.occurrenceStart)
        assertThat(db.tasks().entity(id)!!.status).isEqualTo(TaskStatus.NEEDS_ACTION)

        val override = db.tasks().override(id, target.occurrenceStart)!!
        assertThat(override.uid).isEqualTo(db.tasks().entity(id)!!.uid)
        assertThat(override.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(override.rrule).isNull()
        // The override stands for *that* occurrence, so it carries the
        // occurrence's resolved times, not the master's anchor.
        assertThat(override.dtstart).isEqualTo(target.occurrenceStart)
    }

    @Test
    fun reopeningACompletedOccurrenceReusesItsOverride() {
        val id = source.insertTask(form(title = "Water the plants"))
        makeRecurring(id, now)
        val target = source.tasks(TaskQuery(listId = listId))
            .first { it.taskId == id && it.distanceFromCurrent == 0 }

        source.setCompletedInstance(id, target.occurrenceStart!!, completed = true)
        source.setCompletedInstance(id, target.occurrenceStart, completed = false)

        assertThat(db.tasks().overrides(id)).hasSize(1)
        assertThat(db.tasks().override(id, target.occurrenceStart)!!.status)
            .isEqualTo(TaskStatus.NEEDS_ACTION)
        assertThat(source.tasks(TaskQuery(listId = listId)).map { it.occurrenceStart })
            .contains(target.occurrenceStart)
    }

    @Test
    fun cancellingOneOccurrenceLeavesTheRestOfTheSeriesOpen() {
        val id = source.insertTask(form(title = "Water the plants"))
        makeRecurring(id, now)
        val target = source.tasks(TaskQuery(listId = listId))
            .first { it.taskId == id && it.distanceFromCurrent == 0 }

        source.setCancelledInstance(id, target.occurrenceStart!!, cancelled = true)

        assertThat(db.tasks().entity(id)!!.status).isEqualTo(TaskStatus.NEEDS_ACTION)
        assertThat(db.tasks().override(id, target.occurrenceStart)!!.status).isEqualTo(TaskStatus.CANCELLED)

        source.setCancelledInstance(id, target.occurrenceStart, cancelled = false)

        assertThat(db.tasks().overrides(id)).hasSize(1)
        assertThat(db.tasks().override(id, target.occurrenceStart)!!.status).isEqualTo(TaskStatus.NEEDS_ACTION)
    }

    @Test
    fun completingANonRecurringTaskThroughTheInstancePathWritesTheRowItself() {
        val id = source.insertTask(form(title = "Buy milk", due = now + 1.days))

        source.setCompletedInstance(id, now, completed = true)

        assertThat(db.tasks().overrides(id)).isEmpty()
        assertThat(db.tasks().entity(id)!!.status).isEqualTo(TaskStatus.COMPLETED)
    }

    @Test
    fun anOverrideReplacesOnlyItsOwnOccurrence() {
        val id = source.insertTask(form(title = "Water the plants"))
        makeRecurring(id, now)
        // The list holds this series alone, so no filter is needed — and none can
        // be written on taskId, since the override reports its own row id.
        val before = source.tasks(TaskQuery(listId = listId))
        val target = before.first { it.distanceFromCurrent == 1 }

        source.updateInstance(id, target.occurrenceStart!!, form(title = "Water them twice"))

        val after = source.tasks(TaskQuery(listId = listId))
        assertThat(after).hasSize(before.size)
        val edited = after.single { it.title == "Water them twice" }
        assertThat(edited.occurrenceStart).isEqualTo(target.occurrenceStart)
        assertThat(after.filter { it.occurrenceStart == target.occurrenceStart }).hasSize(1)
    }

    /**
     * An edited occurrence addresses its own row, not the master's. That is what
     * sends the *next* edit down `updateTask` rather than forking a second time:
     * an override carries no rule, so it reads back as non-recurring.
     */
    @Test
    fun anEditedOccurrenceReportsTheOverridesOwnId() {
        val id = source.insertTask(form(title = "Water the plants"))
        makeRecurring(id, now)
        val target = source.tasks(TaskQuery(listId = listId)).first { it.distanceFromCurrent == 1 }

        source.updateInstance(id, target.occurrenceStart!!, form(title = "Water them twice"))

        val edited = source.tasks(TaskQuery(listId = listId)).single { it.title == "Water them twice" }
        val overrideId = db.tasks().override(id, target.occurrenceStart)!!.id
        assertThat(edited.taskId).isEqualTo(overrideId)
        assertThat(edited.taskId).isNotEqualTo(id)
        assertThat(source.task(overrideId)!!.isRecurring).isFalse()
    }

    @Test
    fun editingASeriesDoesNotReAnchorItWhenOneOccurrenceIsEdited() {
        val anchor = now
        val id = source.insertTask(form())
        makeRecurring(id, anchor)
        val target = source.tasks(TaskQuery(listId = listId))
            .filter { it.taskId == id }
            .first { it.distanceFromCurrent == 2 }

        source.updateInstance(id, target.occurrenceStart!!, form(due = now + 99.days))

        assertThat(db.tasks().entity(id)!!.dtstart).isEqualTo(anchor)
    }

    @Test
    fun updatingANonRecurringTaskWritesThroughToItsRow() {
        val id = source.insertTask(form(title = "old"))

        source.updateTask(id, form(title = "new"))

        assertThat(source.task(id)!!.title).isEqualTo("new")
    }

    @Test
    fun completionTogglesTheWholeTriple() {
        val id = source.insertTask(form())

        source.setCompleted(id, completed = true)
        val done = db.tasks().entity(id)!!
        assertThat(done.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(done.percentComplete).isEqualTo(100)
        assertThat(done.completedAt).isNotNull()

        source.setCompleted(id, completed = false)
        assertThat(db.tasks().entity(id)!!.completedAt).isNull()
    }

    @Test
    fun completedTasksAreExcludedUnlessAskedFor() {
        val id = source.insertTask(form())
        source.setCompleted(id, completed = true)

        assertThat(source.tasks(TaskQuery(listId = listId, includeCompleted = false))).isEmpty()
        assertThat(source.tasks(TaskQuery(listId = listId, includeCompleted = true))).hasSize(1)
    }

    @Test
    fun alarmsRoundTripAndReplaceRatherThanAccumulate() {
        val id = source.insertTask(form(due = now + 1.days))

        // The whole reminder, not just the minute count: collapsing it to a bare
        // Int is what fired an imported START-referenced alarm off DUE, and an
        // alarm this seam sets from the UI is always due-referenced.
        source.setAlarm(id, 30)
        assertThat(source.alarms()[id]).isEqualTo(TaskReminder(minutesBefore = 30))

        source.setAlarm(id, 60)
        assertThat(db.alarms().forTask(id)).hasSize(1)
        assertThat(source.alarms()[id]).isEqualTo(TaskReminder(minutesBefore = 60))

        source.setAlarm(id, null)
        assertThat(source.alarms()).doesNotContainKey(id)
    }

    @Test
    fun settingTheEditableReminderLeavesTheOthersAlone() {
        val id = source.insertTask(form(due = now + 1.days))
        val start = TaskReminder(minutesBefore = 10, fromStart = true)
        source.setReminders(id, listOf(start, TaskReminder(30), TaskReminder(120)))

        source.setAlarm(id, 45)
        assertThat(source.reminders()[id]).containsExactly(start, TaskReminder(30), TaskReminder(45)).inOrder()
        assertThat(source.alarms()[id]).isEqualTo(TaskReminder(45))

        source.setAlarm(id, null)
        assertThat(source.reminders()[id]).containsExactly(start, TaskReminder(30)).inOrder()
        assertThat(source.alarms()[id]).isEqualTo(TaskReminder(30))
    }

    @Test
    fun forkingAnOccurrenceCarriesTheReminderOntoIt() {
        val id = source.insertTask(form(due = now + 1.days))
        makeRecurring(id, now)
        source.setAlarm(id, 30)
        val target = source.tasks(TaskQuery(listId = listId))
            .filter { it.taskId == id }
            .first { it.distanceFromCurrent == 1 }

        source.updateInstance(id, target.occurrenceStart!!, form())

        val override = db.tasks().override(id, target.occurrenceStart)!!
        assertThat(db.alarms().forTask(override.id).single().minutesBefore).isEqualTo(30)
    }

    @Test
    fun deletingATaskInALocalListRemovesItOutright() {
        val id = source.insertTask(form())

        source.deleteTask(id)

        // No account knows about it, so there is nothing to tombstone for.
        assertThat(db.tasks().entity(id)).isNull()
    }

    @Test
    fun deletingASeriesTakesItsOverridesWithIt() {
        val id = source.insertTask(form())
        makeRecurring(id, now)
        val target = source.tasks(TaskQuery(listId = listId))
            .filter { it.taskId == id }
            .first { it.distanceFromCurrent == 1 }
        source.updateInstance(id, target.occurrenceStart!!, form(title = "moved"))

        source.deleteTask(id)

        assertThat(db.tasks().allOverrides(listId)).isEmpty()
    }

    @Test
    fun aForkedOccurrenceCarriesTheSeriesReminder() {
        val id = source.insertTask(form())
        makeRecurring(id, now)
        source.setAlarm(id, minutesBeforeDue = 30)
        val target = source.tasks(TaskQuery(listId = listId))
            .filter { it.taskId == id }
            .first { it.distanceFromCurrent == 1 }

        source.updateInstance(id, target.occurrenceStart!!, form(title = "moved"))

        // The fork copies the master's properties, which is what carries the
        // reminder across — the repository is what puts the series' own back.
        val override = db.tasks().override(id, target.occurrenceStart)!!
        assertThat(db.alarms().forTask(override.id).single().minutesBefore).isEqualTo(30)
    }

    @Test
    fun anOccurrenceCannotBeMovedOutOfItsSeriesList() {
        val other = source.createLocalList("Work", 0)
        val id = source.insertTask(form())
        makeRecurring(id, now)
        val target = source.tasks(TaskQuery(listId = listId))
            .filter { it.taskId == id }
            .first { it.distanceFromCurrent == 1 }
        source.updateInstance(id, target.occurrenceStart!!, form(title = "moved"))
        val override = db.tasks().override(id, target.occurrenceStart)!!

        source.updateTask(override.id, TaskForm(title = "moved", listId = other))

        // ⚠️ list_id = B with master_id in list A is invisible in both — the task
        // query skips non-null master_id, and the override query finds no master
        // in B — while still uploading as part of A's resource.
        assertThat(db.tasks().entity(override.id)!!.listId).isEqualTo(listId)
        assertThat(db.tasks().entity(override.id)!!.title).isEqualTo("moved")
    }

    @Test
    fun deletingASyncedSeriesTombstonesItsOverridesToo() {
        val syncedList = syncedList()
        val id = source.insertTask(TaskForm(title = "Standup", listId = syncedList))
        makeRecurring(id, now)
        val target = source.tasks(TaskQuery(listId = syncedList))
            .filter { it.taskId == id }
            .first { it.distanceFromCurrent == 1 }
        source.updateInstance(id, target.occurrenceStart!!, TaskForm(title = "moved", listId = syncedList))

        source.deleteTask(id)

        // ⚠️ master_id cascades on delete, and a tombstone deletes nothing — so
        // a master marked alone left the resource reading as partly deleted, the
        // DELETE was never sent, and the task stayed on the server for ever.
        val rows = db.tasks().allIn(syncedList)
        assertThat(rows).hasSize(2)
        assertThat(rows.all { it.isDeleted && it.isDirty }).isTrue()
    }

    @Test
    fun deletingOneOccurrenceExceptsItOnTheMaster() {
        val id = source.insertTask(form())
        makeRecurring(id, now)
        val target = source.tasks(TaskQuery(listId = listId))
            .filter { it.taskId == id }
            .first { it.distanceFromCurrent == 1 }
        source.updateInstance(id, target.occurrenceStart!!, form(title = "moved"))
        val override = db.tasks().override(id, target.occurrenceStart)!!

        source.deleteTask(override.id)

        // ⚠️ Dropping the override row un-overrides the occurrence, and the
        // master's RRULE regenerates it. The EXDATE is the deletion.
        assertThat(db.tasks().entity(override.id)).isNull()
        assertThat(db.tasks().entity(id)!!.exdate).isNotEmpty()
        assertThat(source.tasks(TaskQuery(listId = listId)).map { it.occurrenceStart })
            .doesNotContain(target.occurrenceStart)
    }

    @Test
    fun subtasksReadBackUnderTheirParent() {
        val parent = source.insertTask(form(title = "Prepare invoice"))
        val child = source.insertTask(form(title = "Gather receipts").copy(parentId = parent))

        assertThat(source.subtasks(parent).map { it.taskId }).containsExactly(child)
    }

    @Test
    fun exportReadsMastersNotOccurrences() {
        val id = source.insertTask(form(title = "Water the plants"))
        makeRecurring(id, now)

        val exported = source.exportTasks(listId)

        // One row carrying the rule, not one row per occurrence with the rule lost.
        assertThat(exported).hasSize(1)
        assertThat(exported.single().rrule).isEqualTo("FREQ=WEEKLY")
        assertThat(exported.single().uid).isNotEmpty()
    }

    @Test
    fun insertingIntoAMissingListFails() {
        val thrown = runCatching { source.insertTask(form().copy(listId = 9_999)) }.exceptionOrNull()

        assertThat(thrown).isNotNull()
    }
}

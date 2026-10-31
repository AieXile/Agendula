package com.aiexile.agendula.data.tasks.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.domain.TaskStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant

/**
 * The schema, exercised through the DAOs. Instrumented rather than JVM because
 * the app's unit tests are plain JUnit 5 with no Robolectric, and Room needs a
 * real SQLite.
 */
@RunWith(AndroidJUnit4::class)
class TasksDatabaseTest {

    private lateinit var db: TasksDatabase
    private lateinit var lists: TaskListDao
    private lateinit var tasks: TaskDao
    private lateinit var alarms: TaskAlarmDao
    private lateinit var accounts: AccountDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TasksDatabase::class.java,
        ).allowMainThreadQueries().build()
        lists = db.taskLists()
        tasks = db.tasks()
        alarms = db.alarms()
        accounts = db.accounts()
    }

    @After
    fun tearDown() = db.close()

    private fun newList(name: String = "Groceries", accountId: Long? = null): Long =
        lists.insert(TaskListEntity(name = name, color = 0xFF00FF00.toInt(), accountId = accountId))

    private fun newTask(
        listId: Long,
        uid: String = "uid-${counter++}",
        title: String? = "Buy milk",
        status: TaskStatus = TaskStatus.NEEDS_ACTION,
        parentId: Long? = null,
        masterId: Long? = null,
        recurrenceId: Instant? = null,
    ): Long = tasks.insert(
        TaskEntity(
            listId = listId,
            uid = uid,
            title = title,
            status = status,
            parentId = parentId,
            masterId = masterId,
            recurrenceId = recurrenceId,
        ),
    )

    @Test
    fun writesAndReadsAListWithItsTasks() {
        val accountId = accounts.insert(AccountEntity(displayName = "Fastmail"))
        val listId = newList(accountId = accountId)
        val due = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val taskId = tasks.insert(
            TaskEntity(
                listId = listId,
                uid = "uid-1",
                title = "Buy milk",
                description = "2%",
                due = due,
                priority = 3,
                status = TaskStatus.IN_PROCESS,
                percentComplete = 40,
            ),
        )

        val list = lists.lists().single()
        assertThat(list.list.id).isEqualTo(listId)
        assertThat(list.list.name).isEqualTo("Groceries")
        assertThat(list.accountDisplayName).isEqualTo("Fastmail")

        val row = tasks.task(taskId)!!
        assertThat(row.task.title).isEqualTo("Buy milk")
        assertThat(row.task.due).isEqualTo(due)
        // Stored raw: an off-bucket PRIORITY must come back as it went in.
        assertThat(row.task.priority).isEqualTo(3)
        assertThat(row.task.status).isEqualTo(TaskStatus.IN_PROCESS)
        assertThat(row.task.percentComplete).isEqualTo(40)
        assertThat(row.listName).isEqualTo("Groceries")
        assertThat(row.accountDisplayName).isEqualTo("Fastmail")
    }

    @Test
    fun readsTasksOfOneListAndHidesClosedOnesUnlessAsked() {
        val a = newList("A")
        val b = newList("B")
        newTask(a, title = "open")
        newTask(a, title = "done", status = TaskStatus.COMPLETED)
        newTask(a, title = "cancelled", status = TaskStatus.CANCELLED)
        newTask(b, title = "elsewhere")

        assertThat(tasks.tasks(a, includeCompleted = false).map { it.task.title })
            .containsExactly("open")
        assertThat(tasks.tasks(a, includeCompleted = true)).hasSize(3)
        assertThat(tasks.tasks(null, includeCompleted = true)).hasSize(4)
    }

    @Test
    fun readsSubtasksByParent() {
        val listId = newList()
        val parent = newTask(listId, title = "parent")
        newTask(listId, title = "child", parentId = parent)

        assertThat(tasks.subtasks(parent).map { it.task.title }).containsExactly("child")
    }

    @Test
    fun hidesTombstonesFromReadsAndExports() {
        val listId = newList()
        val taskId = newTask(listId)
        tasks.markDeleted(taskId, Instant.fromEpochMilliseconds(1))

        assertThat(tasks.tasks(listId, includeCompleted = true)).isEmpty()
        assertThat(tasks.task(taskId)).isNull()
        assertThat(tasks.exportTasks(listId)).isEmpty()
        assertThat(tasks.entity(taskId)).isNotNull()
    }

    @Test
    fun keepsOverridesOutOfTheMasterReads() {
        val listId = newList()
        val master = newTask(listId, uid = "series")
        val override = newTask(
            listId,
            uid = "series",
            masterId = master,
            recurrenceId = Instant.fromEpochMilliseconds(5_000),
        )

        assertThat(tasks.tasks(listId, includeCompleted = true).map { it.task.id })
            .containsExactly(master)
        assertThat(tasks.overrides(master).map { it.id }).containsExactly(override)
        assertThat(tasks.allOverrides(listId).map { it.id }).containsExactly(override)
        assertThat(tasks.override(master, Instant.fromEpochMilliseconds(5_000))?.id)
            .isEqualTo(override)
        assertThat(tasks.exportTasks(listId).map { it.id }).containsExactly(master)
    }

    // --- cascades -------------------------------------------------------------

    @Test
    fun deletingAListDeletesItsTasks() {
        val listId = newList()
        val taskId = newTask(listId)

        lists.delete(listId)

        assertThat(tasks.entity(taskId)).isNull()
    }

    @Test
    fun deletingASeriesDeletesItsOverrides() {
        val listId = newList()
        val master = newTask(listId, uid = "series")
        val override = newTask(
            listId,
            uid = "series",
            masterId = master,
            recurrenceId = Instant.fromEpochMilliseconds(5_000),
        )

        tasks.delete(master)

        assertThat(tasks.entity(override)).isNull()
    }

    @Test
    fun deletingAParentPromotesItsSubtasks() {
        val listId = newList()
        val parent = newTask(listId, title = "parent")
        val child = newTask(listId, title = "child", parentId = parent)

        tasks.delete(parent)

        val promoted = tasks.entity(child)
        assertThat(promoted).isNotNull()
        assertThat(promoted!!.parentId).isNull()
    }

    @Test
    fun deletingATaskDeletesItsAlarms() {
        val listId = newList()
        val taskId = newTask(listId)
        alarms.replaceForTask(taskId, TaskAlarmEntity(taskId = taskId, minutesBefore = 15))
        assertThat(alarms.all()).hasSize(1)

        tasks.delete(taskId)

        assertThat(alarms.all()).isEmpty()
    }

    @Test
    fun deletingAnAccountDetachesItsListsInsteadOfDeletingThem() {
        val accountId = accounts.insert(AccountEntity(displayName = "Fastmail"))
        val listId = newList(accountId = accountId)

        accounts.delete(accountId)

        assertThat(lists.entity(listId)!!.accountId).isNull()
    }

    @Test
    fun replacingAnAlarmLeavesOnlyTheNewOne() {
        val listId = newList()
        val taskId = newTask(listId)
        alarms.replaceForTask(taskId, TaskAlarmEntity(taskId = taskId, minutesBefore = 15))
        alarms.replaceForTask(taskId, TaskAlarmEntity(taskId = taskId, minutesBefore = 30))

        assertThat(alarms.forTask(taskId).map { it.minutesBefore }).containsExactly(30)
        assertThat(alarms.forTask(taskId).single().reference).isEqualTo(AlarmReference.DUE)

        alarms.replaceForTask(taskId, null)
        assertThat(alarms.forTask(taskId)).isEmpty()
    }

    // --- the unique index -----------------------------------------------------

    @Test
    fun anOverrideMayShareItsMastersUid() {
        val listId = newList()
        val master = newTask(listId, uid = "series")
        newTask(listId, uid = "series", masterId = master, recurrenceId = Instant.fromEpochMilliseconds(1))
        newTask(listId, uid = "series", masterId = master, recurrenceId = Instant.fromEpochMilliseconds(2))

        assertThat(tasks.overrides(master)).hasSize(2)
    }

    @Test
    fun rejectsTwoOverridesOfTheSameOccurrence() {
        val listId = newList()
        val master = newTask(listId, uid = "series")
        val at = Instant.fromEpochMilliseconds(1)
        newTask(listId, uid = "series", masterId = master, recurrenceId = at)

        val failure = runCatching {
            newTask(listId, uid = "series", masterId = master, recurrenceId = at)
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(failure!!.message).contains("UNIQUE")
    }

    @Test
    fun theSameUidMayExistInAnotherList() {
        val a = newList("A")
        val b = newList("B")
        newTask(a, uid = "shared")
        newTask(b, uid = "shared")

        assertThat(tasks.byUid(a, "shared")).isNotNull()
        assertThat(tasks.byUid(b, "shared")).isNotNull()
    }

    private companion object {
        var counter = 0
    }
}

package de.jeanlucmakiola.agendula.data.tasks.transfer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.data.tasks.ProviderEnvironment
import de.jeanlucmakiola.agendula.data.tasks.ProviderResolver
import de.jeanlucmakiola.agendula.data.tasks.TaskQuery
import de.jeanlucmakiola.agendula.data.tasks.TaskReminder
import de.jeanlucmakiola.agendula.data.tasks.TasksDataSource
import de.jeanlucmakiola.agendula.data.tasks.room.AlarmReference
import de.jeanlucmakiola.agendula.data.tasks.room.TasksDatabase
import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskForm
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.domain.TaskStatus
import de.jeanlucmakiola.agendula.domain.export.ExportTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Provider
import kotlin.time.Instant

/**
 * The copy out of an external provider and into Room — the upgrade path every
 * released install actually needs, since no release ever bundled the provider
 * `OneShotImport` reads.
 *
 * The source is a fake [TasksDataSource] rather than a live OpenTasks: what is
 * worth testing is the write half — id remapping, uid collisions, verified
 * counts, the once-only guard — and pinning that to a device with a third-party
 * app installed would mean it never ran. Instrumented all the same, because the
 * destination is a real Room database in a real transaction.
 */
@RunWith(AndroidJUnit4::class)
class ExternalImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: DataStore<Preferences>
    private lateinit var db: TasksDatabase
    private lateinit var source: FakeExternalStore
    private lateinit var importer: ExternalImport

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefs = PreferenceDataStoreFactory.create(scope = scope) {
            temp.newFile("transfer-${counter++}.preferences_pb").also(File::delete)
        }
        db = Room.inMemoryDatabaseBuilder(context, TasksDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        source = FakeExternalStore()
        importer = ExternalImport(
            external = Provider { source },
            resolver = ProviderResolver(NoProviderInstalled),
            database = db,
            dataStore = prefs,
            io = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    @Test
    fun copiesListsTasksAndAlarms() = runBlocking {
        source.lists = listOf(list(7, "Errands"), list(9, "Work"))
        source.tasks = mapOf(
            7L to listOf(task(100, "Milk"), task(101, "Bread")),
            9L to listOf(task(200, "Invoice")),
        )
        source.alarms = mapOf(100L to TaskReminder(minutesBefore = 30))

        val result = importer.run()

        assertThat(result).isEqualTo(
            TransferResult.Copied(TransferCounts(lists = 2, tasks = 3, alarms = 1)),
        )
        assertThat(db.taskLists().lists().map { it.list.name })
            .containsExactly("Errands", "Work")
        assertThat(db.tasks().tasks(listId = null, includeCompleted = true).map { it.task.title })
            .containsExactly("Milk", "Bread", "Invoice")
        assertThat(importer.hasRun.first()).isTrue()
    }

    /** Every list arrives device-only: the account belongs to the sync app. */
    @Test
    fun importedListsAreDeviceOnly() = runBlocking {
        source.lists = listOf(list(7, "Shared", accountName = "me@example.org"))
        source.tasks = mapOf(7L to listOf(task(100, "Milk")))

        importer.run()

        assertThat(db.taskLists().lists().single().list.accountId).isNull()
    }

    /** Provider row ids are the source's; Room mints its own and the link follows. */
    @Test
    fun remapsParentIdsOntoTheNewRowIds() = runBlocking {
        source.lists = listOf(list(7, "Errands"))
        // Child before parent, so a naive single pass would not find the parent.
        source.tasks = mapOf(
            7L to listOf(task(100, "Subtask", parentId = 200), task(200, "Parent")),
        )

        importer.run()

        val rows = db.tasks().tasks(listId = null, includeCompleted = true).map { it.task }
        val parent = rows.single { it.title == "Parent" }
        val child = rows.single { it.title == "Subtask" }
        assertThat(child.parentId).isEqualTo(parent.id)
        assertThat(child.parentId).isNotEqualTo(200L)
    }

    /**
     * A `RECURRENCE-ID` override reaches the read seam as another master-shaped row
     * sharing its series' uid. The unique index on (list, uid, recurrence_id)
     * would reject it and take the whole copy down, so it gets a fresh uid.
     */
    @Test
    fun aDuplicateUidDoesNotAbortTheCopy() = runBlocking {
        source.lists = listOf(list(7, "Errands"))
        source.tasks = mapOf(
            7L to listOf(
                task(100, "Weekly", uid = "shared-uid"),
                task(101, "Weekly, that one week", uid = "shared-uid"),
            ),
        )

        val result = importer.run()

        assertThat(result).isInstanceOf(TransferResult.Copied::class.java)
        val uids = db.tasks().tasks(listId = null, includeCompleted = true).map { it.task.uid }
        assertThat(uids).hasSize(2)
        assertThat(uids.toSet()).hasSize(2)
        assertThat(uids).contains("shared-uid")
    }

    /** A START-referenced reminder must not come across as a before-due one. */
    @Test
    fun preservesTheAlarmReference() = runBlocking {
        source.lists = listOf(list(7, "Errands"))
        source.tasks = mapOf(7L to listOf(task(100, "Standup")))
        source.alarms = mapOf(100L to TaskReminder(minutesBefore = 10, fromStart = true))

        importer.run()

        val alarm = db.alarms().all().single()
        assertThat(alarm.reference).isEqualTo(AlarmReference.START)
        assertThat(alarm.minutesBefore).isEqualTo(10)
    }

    @Test
    fun anEmptySourceWritesNothingAndIsNotMarkedDone() = runBlocking {
        val result = importer.run()

        assertThat(result).isEqualTo(TransferResult.NothingToCopy)
        assertThat(db.taskLists().lists()).isEmpty()
        // Still on offer: there was nothing to copy, not a copy that happened.
        assertThat(importer.hasRun.first()).isFalse()
    }

    /** A read that blows up must leave Room exactly as it was. */
    @Test
    fun aFailedReadRollsBackAndLeavesTheGuardOpen() = runBlocking {
        source.lists = listOf(list(7, "Errands"))
        source.failOnExport = true

        val result = importer.run()

        assertThat(result).isInstanceOf(TransferResult.Failed::class.java)
        assertThat(db.taskLists().lists()).isEmpty()
        assertThat(importer.hasRun.first()).isFalse()
    }

    @Test
    fun previewCountsWhatARunWouldWrite() = runBlocking {
        source.lists = listOf(list(7, "Errands"), list(9, "Work"))
        source.tasks = mapOf(
            7L to listOf(task(100, "Milk"), task(101, "Bread")),
            9L to listOf(task(200, "Invoice")),
        )
        source.alarms = mapOf(100L to TaskReminder(minutesBefore = 30))
        // preview() resolves the provider itself, so it needs one to be installed.
        val withProvider = ExternalImport(
            external = Provider { source },
            resolver = ProviderResolver(OpenTasksInstalledAndGranted),
            database = db,
            dataStore = prefs,
            io = Dispatchers.IO,
        )

        assertThat(withProvider.preview())
            .isEqualTo(TransferCounts(lists = 2, tasks = 3, alarms = 1))
    }

    @Test
    fun previewIsNullWithoutAReadableProvider() = runBlocking {
        assertThat(importer.preview()).isNull()
    }

    // --- fixtures --------------------------------------------------------------

    private fun list(id: Long, name: String, accountName: String = "Device") = TaskList(
        id = id,
        name = name,
        color = 0xFF7E57C2.toInt(),
        accountName = accountName,
        accountType = "org.dmfs.account.LOCAL",
        isSynced = true,
        isVisible = true,
        owner = null,
    )

    private fun task(
        id: Long,
        title: String,
        uid: String? = "uid-$id",
        parentId: Long? = null,
    ) = ExportTask(
        taskId = id,
        uid = uid,
        title = title,
        description = null,
        location = null,
        url = null,
        priority = Priority.NONE,
        status = TaskStatus.NEEDS_ACTION,
        percentComplete = null,
        start = null,
        due = Instant.fromEpochMilliseconds(1_800_000_000_000),
        isAllDay = false,
        completedAt = null,
        created = null,
        lastModified = null,
        rrule = null,
        rdate = null,
        parentId = parentId,
    )

    private companion object {
        var counter = 0
    }
}

/** Only the three reads the copy makes; everything else is out of scope. */
private class FakeExternalStore : TasksDataSource {
    var lists: List<TaskList> = emptyList()
    var tasks: Map<Long, List<ExportTask>> = emptyMap()
    var alarms: Map<Long, TaskReminder> = emptyMap()
    var failOnExport = false

    override fun taskLists(): List<TaskList> = lists

    override fun exportTasks(listId: Long): List<ExportTask> {
        if (failOnExport) error("provider went away mid-read")
        return tasks[listId].orEmpty()
    }

    override fun alarms(): Map<Long, TaskReminder> = alarms

    override fun tasks(query: TaskQuery): List<Task> = unused()
    override fun task(taskId: Long): Task? = unused()
    override fun subtasks(parentTaskId: Long): List<Task> = unused()
    override fun insertTask(form: TaskForm): Long = unused()
    override fun updateTask(taskId: Long, form: TaskForm) = unused()
    override fun updateInstance(taskId: Long, occurrenceStart: Instant, form: TaskForm) = unused()
    override fun setAlarm(taskId: Long, minutesBeforeDue: Int?) = unused()
    override fun setReminders(taskId: Long, reminders: List<TaskReminder>) = unused()
    override fun setCompleted(taskId: Long, completed: Boolean) = unused()
    override fun setCompletedInstance(taskId: Long, occurrenceStart: Instant, completed: Boolean) = unused()
    override fun deleteTask(taskId: Long) = unused()
    override fun setCancelled(taskId: Long, cancelled: Boolean) = unused()
    override fun setCancelledInstance(taskId: Long, occurrenceStart: Instant, cancelled: Boolean) = unused()
    override fun updateSeries(seriesId: Long, occurrenceStart: Instant, form: TaskForm) = unused()
    override fun splitSeries(seriesId: Long, occurrenceStart: Instant, form: TaskForm): Long = unused()
    override fun deleteOccurrence(seriesId: Long, occurrenceStart: Instant) = unused()
    override fun deleteFollowing(seriesId: Long, occurrenceStart: Instant) = unused()
    override fun createLocalList(name: String, color: Int): Long = unused()
    override fun updateList(listId: Long, name: String, color: Int) = unused()
    override fun deleteList(listId: Long) = unused()
    override fun registerObserver(onChange: () -> Unit): AutoCloseable = unused()

    private fun unused(): Nothing = error("the copy does not call this")
}

/** No tasks provider on the device: `preview()` has nothing to read. */
private object NoProviderInstalled : ProviderEnvironment {
    override fun packageDeclaring(authority: String): String? = null
    override fun isGranted(permission: String): Boolean = false
    override fun appLabel(packageName: String): String? = null
}

private object OpenTasksInstalledAndGranted : ProviderEnvironment {
    override fun packageDeclaring(authority: String): String? =
        "org.dmfs.tasks".takeIf { authority == "org.dmfs.tasks" }

    override fun isGranted(permission: String): Boolean = permission.startsWith("org.dmfs.permission.")
    override fun appLabel(packageName: String): String = "OpenTasks"
}

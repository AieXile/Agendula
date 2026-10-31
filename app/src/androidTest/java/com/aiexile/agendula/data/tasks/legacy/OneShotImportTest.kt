package com.aiexile.agendula.data.tasks.legacy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.data.tasks.room.AlarmReference
import com.aiexile.agendula.data.tasks.room.TaskEntity
import com.aiexile.agendula.data.tasks.room.TasksDatabase
import com.aiexile.agendula.domain.TaskStatus
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
import java.util.UUID
import kotlin.time.Instant

/**
 * The one-shot import, against `assets/tasks-v23.db` — the dmfs v23 fixture
 * `scripts/make_import_fixture.py` seeds. Instrumented because both halves need
 * a real SQLite: the source file and Room.
 */
@RunWith(AndroidJUnit4::class)
class OneShotImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: DataStore<Preferences>
    private lateinit var db: TasksDatabase
    private lateinit var importer: OneShotImport

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefs = PreferenceDataStoreFactory.create(scope = scope) {
            temp.newFile("import-${counter++}.preferences_pb").also(File::delete)
        }
        db = Room.inMemoryDatabaseBuilder(context, TasksDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        importer = OneShotImport(context, db, prefs)
        legacyFile().delete()
        archiveFile().delete()
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        legacyFile().delete()
        archiveFile().delete()
    }

    private fun legacyFile() = context.getDatabasePath(OneShotImport.LEGACY_NAME)
    private fun archiveFile() = context.getDatabasePath(OneShotImport.ARCHIVE_NAME)

    /** The fixture, copied out of the test APK's assets. */
    private fun fixture(target: File = temp.newFile("tasks-v23-copy.db")): File {
        InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE).use { source ->
            target.outputStream().use(source::copyTo)
        }
        return target
    }

    private fun taskRows(): Map<String, TaskEntity> =
        db.tasks().tasks(null, includeCompleted = true).associate { it.task.title!! to it.task }

    // --- what lands -----------------------------------------------------------

    @Test
    fun importsEveryLiveTaskAndLeavesTheDeletedOneBehind() {
        val counts = importer.importFrom(fixture())

        assertThat(counts).isEqualTo(ImportCounts(lists = 3, tasks = 8, alarms = 2))
        assertThat(taskRows().keys).containsExactly(
            "Buy milk",
            "Call the dentist",
            "Gather receipts",
            "Renew domain",
            "Water the plants",
            "Team offsite",
            "Task in a hidden list",
            "Ship the release",
        )
    }

    @Test
    fun importsEveryListAsADeviceOnlyListWithItsFlags() {
        importer.importFrom(fixture())

        val lists = db.taskLists().lists().associateBy { it.list.name }
        assertThat(lists.keys).containsExactly("Personal", "Hidden list", "Work")
        assertThat(lists.values.map { it.list.accountId }).containsExactly(null, null, null)
        assertThat(lists.getValue("Personal").list.isVisible).isTrue()
        assertThat(lists.getValue("Hidden list").list.isVisible).isFalse()
        // The list that sat under a real account: still imported, owner kept.
        assertThat(lists.getValue("Work").list.owner).isEqualTo("Me")
        assertThat(lists.getValue("Work").list.color).isEqualTo(0xFF2244AA.toInt())
    }

    @Test
    fun carriesTheTaskFieldsAcross() {
        importer.importFrom(fixture())
        val tasks = taskRows()

        val milk = tasks.getValue("Buy milk")
        assertThat(milk.due).isEqualTo(Instant.fromEpochMilliseconds(T0 + DAY))
        assertThat(milk.status).isEqualTo(TaskStatus.NEEDS_ACTION)
        assertThat(milk.createdAt).isEqualTo(Instant.fromEpochMilliseconds(T0))

        val dentist = tasks.getValue("Call the dentist")
        assertThat(dentist.status).isEqualTo(TaskStatus.IN_PROCESS)
        assertThat(dentist.percentComplete).isEqualTo(40)

        val domain = tasks.getValue("Renew domain")
        assertThat(domain.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(domain.completedAt).isEqualTo(Instant.fromEpochMilliseconds(T0 - DAY))

        val plants = tasks.getValue("Water the plants")
        assertThat(plants.rrule).isEqualTo("FREQ=WEEKLY;BYDAY=MO,TH")
        assertThat(plants.timezone).isEqualTo("Europe/Berlin")
        assertThat(plants.dtstart).isEqualTo(Instant.fromEpochMilliseconds(T0))

        assertThat(tasks.getValue("Team offsite").isAllDay).isTrue()
    }

    // --- uids -----------------------------------------------------------------

    @Test
    fun keepsExistingUidsAndMintsOneWhereTheLegacyRowHadNone() {
        importer.importFrom(fixture())
        val tasks = taskRows()

        assertThat(tasks.getValue("Buy milk").uid).isEqualTo("a1b2c3d4-0000-4000-8000-000000000001")
        // The external-account row's uid is what lets it be re-attached later.
        assertThat(tasks.getValue("Ship the release").uid)
            .isEqualTo("a1b2c3d4-0000-4000-8000-000000000009")

        val minted = tasks.getValue("Call the dentist").uid
        assertThat(minted).isNotEmpty()
        assertThat(UUID.fromString(minted).version()).isEqualTo(4)
        assertThat(tasks.values.map { it.uid }.toSet()).hasSize(tasks.size)
    }

    // --- the id remap ---------------------------------------------------------

    @Test
    fun remapsListIdsOntoTheNewRowIds() {
        importer.importFrom(fixture())

        val lists = db.taskLists().lists().associateBy { it.list.name }
        val byList = db.tasks().tasks(null, includeCompleted = true)
            .groupBy { it.task.listId }
            .mapValues { (_, rows) -> rows.size }

        assertThat(byList[lists.getValue("Personal").list.id]).isEqualTo(6)
        assertThat(byList[lists.getValue("Hidden list").list.id]).isEqualTo(1)
        assertThat(byList[lists.getValue("Work").list.id]).isEqualTo(1)
        // No task kept a dmfs row id that Room never handed out.
        assertThat(byList.keys).containsExactlyElementsIn(lists.values.map { it.list.id })
    }

    @Test
    fun remapsParentIdsOntoTheNewRowIds() {
        importer.importFrom(fixture())
        val tasks = taskRows()

        val parent = tasks.getValue("Buy milk")
        val child = tasks.getValue("Gather receipts")
        assertThat(child.parentId).isEqualTo(parent.id)
        assertThat(db.tasks().subtasks(parent.id).map { it.task.title }).containsExactly("Gather receipts")
        assertThat(tasks.values.filter { it.parentId != null }).hasSize(1)
    }

    // --- alarms ---------------------------------------------------------------

    @Test
    fun importsAlarmsAndSkipsEveryOtherProperty() {
        importer.importFrom(fixture())
        val tasks = taskRows()

        assertThat(db.alarms().all()).hasSize(2)

        val milk = db.alarms().forTask(tasks.getValue("Buy milk").id).single()
        assertThat(milk.minutesBefore).isEqualTo(30)
        assertThat(milk.reference).isEqualTo(AlarmReference.DUE)
        assertThat(milk.message).isNull()

        val release = db.alarms().forTask(tasks.getValue("Ship the release").id).single()
        assertThat(release.minutesBefore).isEqualTo(1440)
        assertThat(release.reference).isEqualTo(AlarmReference.DUE)
        assertThat(release.message).isEqualTo("Ship it")

        // The category property on task 1 is not an alarm.
        assertThat(db.alarms().all().map { it.message }).doesNotContain("Errands")
    }

    @Test
    fun importsCategoryPropertiesAsTags() {
        importer.importFrom(fixture())
        val tasks = taskRows()

        assertThat(tasks.getValue("Buy milk").categories).containsExactly("Errands")
        assertThat(tasks.getValue("Ship the release").categories).isEmpty()
    }

    // --- running it -----------------------------------------------------------

    @Test
    fun runIfNeededImportsArchivesTheSourceAndThenDoesNothing() = runBlocking {
        fixture(legacyFile())

        val first = importer.runIfNeeded()

        assertThat(first).isEqualTo(ImportResult.Imported(ImportCounts(3, 8, 2)))
        assertThat(legacyFile().exists()).isFalse()
        assertThat(archiveFile().exists()).isTrue()
        assertThat(importer.isDone.first()).isTrue()

        val second = importer.runIfNeeded()

        assertThat(second).isEqualTo(ImportResult.AlreadyDone)
        assertThat(taskRows()).hasSize(8)
    }

    @Test
    fun anInterruptedImportResumesFromTheArchiveWithoutDoubling() = runBlocking {
        // The process dying between the commit and the flag write is the one gap
        // the DataStore flag cannot cover on its own. Because the rename happens
        // first and the import always replaces, the next run finds the archive and
        // redoes the same work rather than importing a second copy.
        fixture(legacyFile())
        importer.runIfNeeded()
        importer.clearCompletion()

        val resumed = importer.runIfNeeded()

        assertThat(resumed).isEqualTo(ImportResult.Imported(ImportCounts(3, 8, 2)))
        assertThat(taskRows()).hasSize(8)
        assertThat(db.taskLists().lists()).hasSize(3)
        assertThat(db.alarms().all()).hasSize(2)
    }

    @Test
    fun runIfNeededMarksItselfDoneWhenThereIsNoLegacyDatabase() = runBlocking {
        assertThat(importer.runIfNeeded()).isEqualTo(ImportResult.NothingToImport)
        assertThat(importer.isDone.first()).isTrue()
        assertThat(taskRows()).isEmpty()
    }

    @Test
    fun reimportFromTheArchiveReplacesRatherThanMerges() = runBlocking {
        fixture(legacyFile())
        importer.runIfNeeded()

        val again = importer.reimportFromArchive()

        assertThat(again).isEqualTo(ImportResult.Imported(ImportCounts(3, 8, 2)))
        assertThat(db.taskLists().lists()).hasSize(3)
        assertThat(taskRows()).hasSize(8)
        assertThat(db.alarms().all()).hasSize(2)
        assertThat(archiveFile().exists()).isTrue()
    }

    @Test
    fun replacingTwiceFromTheSameFileLeavesOneCopy() {
        importer.importFrom(fixture())
        val counts = importer.importFrom(fixture(temp.newFile("second.db")), replaceExisting = true)

        assertThat(counts).isEqualTo(ImportCounts(3, 8, 2))
        assertThat(taskRows()).hasSize(8)
        assertThat(db.taskLists().lists()).hasSize(3)
        assertThat(db.alarms().all()).hasSize(2)
    }

    private companion object {
        const val FIXTURE = "tasks-v23.db"
        const val T0 = 1_768_467_600_000L
        const val DAY = 86_400_000L
        var counter = 0
    }
}

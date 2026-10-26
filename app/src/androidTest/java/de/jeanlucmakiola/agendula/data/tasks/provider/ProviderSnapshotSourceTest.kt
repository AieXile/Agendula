package de.jeanlucmakiola.agendula.data.tasks.provider

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.data.tasks.ProviderEnvironment
import de.jeanlucmakiola.agendula.data.tasks.ProviderResolver
import de.jeanlucmakiola.agendula.data.tasks.StorageMode
import de.jeanlucmakiola.agendula.data.tasks.TasksContract
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Instances
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Lists
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Tasks
import de.jeanlucmakiola.agendula.data.tasks.room.TaskAlarmEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TaskEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TaskListEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TasksDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The snapshot follows the Room store, the storage mode and the clock, and says when it moved. */
@RunWith(AndroidJUnit4::class)
class ProviderSnapshotSourceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: TasksDatabase
    private lateinit var resolver: ProviderResolver
    private lateinit var source: ProviderSnapshotSource
    private val clock = MutableClock(Instant.parse("2026-10-10T08:00:00Z"))
    private val changes = LinkedBlockingQueue<Uri>()
    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            changes += uri ?: Uri.EMPTY
        }
    }
    private var listId = 0L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, TasksDatabase::class.java).allowMainThreadQueries().build()
        resolver = ProviderResolver(NoProviderInstalled)
        source = ProviderSnapshotSource(context, db, resolver, awaitStartup = {}, clock = clock)
        listId = db.taskLists().insert(TaskListEntity(name = "Home", color = 0xFF112233.toInt()))
        context.contentResolver.registerContentObserver(TasksContract.authorityUri(source.authority), true, observer)
    }

    @After
    fun tearDown() {
        context.contentResolver.unregisterContentObserver(observer)
        db.close()
    }

    private fun query(table: ProviderTable, selection: String? = null, args: Array<String>? = null): Cursor =
        source.query(table, null, selection, args, null)

    private fun titles(): List<String?> = query(ProviderTable.TASKS).use { cursor ->
        val index = cursor.getColumnIndexOrThrow(Tasks.TITLE)
        buildList { while (cursor.moveToNext()) add(cursor.getString(index)) }
    }

    private fun insert(title: String, due: Instant? = null, rrule: String? = null): Long =
        db.tasks().insert(TaskEntity(listId = listId, uid = title, title = title, due = due, rrule = rrule, timezone = "UTC"))

    /** Waits for the coalesced notification, then drains any stragglers. */
    private fun awaitChange() {
        assertThat(changes.poll(5, TimeUnit.SECONDS)).isNotNull()
        Thread.sleep(700)
        changes.clear()
    }

    private fun assertNoChange() {
        assertThat(changes.poll(1500, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun servesTheOwnStore() {
        val id = insert("Milk")
        db.alarms().insert(TaskAlarmEntity(taskId = id, minutesBefore = 10))

        assertThat(titles()).containsExactly("Milk")
        query(ProviderTable.LISTS).use {
            it.moveToFirst()
            assertThat(it.getString(it.getColumnIndexOrThrow(Lists.NAME))).isEqualTo("Home")
        }
        query(ProviderTable.INSTANCES).use { assertThat(it.count).isEqualTo(1) }
        query(ProviderTable.PROPERTIES).use { assertThat(it.count).isEqualTo(1) }
    }

    @Test
    fun aDeletedTaskIsNotPublished() {
        val id = insert("Gone")
        insert("Kept")
        db.tasks().markDeleted(id, clock.now())

        assertThat(titles()).containsExactly("Kept")
    }

    @Test
    fun aWriteNotifiesObserversAndIsServedNext() {
        source.start()
        assertThat(titles()).isEmpty()

        insert("Later")

        awaitChange()
        assertThat(titles()).containsExactly("Later")
    }

    @Test
    fun aBurstOfWritesNotifiesOnce() {
        source.start()
        titles()

        repeat(5) { insert("t$it") }

        assertThat(changes.poll(5, TimeUnit.SECONDS)).isNotNull()
        assertNoChange()
    }

    @Test
    fun externalModeServesNothing() {
        insert("Mine")
        resolver.storageMode = StorageMode.EXTERNAL

        ProviderTable.entries.forEach { table -> query(table).use { assertThat(it.count).isEqualTo(0) } }
    }

    @Test
    fun switchingModeNotifiesAndRebuilds() {
        source.start()
        insert("Mine")
        assertThat(titles()).containsExactly("Mine")
        changes.clear()

        resolver.storageMode = StorageMode.EXTERNAL
        awaitChange()
        assertThat(titles()).isEmpty()

        resolver.storageMode = StorageMode.OWN
        awaitChange()
        assertThat(titles()).containsExactly("Mine")
    }

    @Test
    fun writesInExternalModeDoNotNotify() {
        resolver.storageMode = StorageMode.EXTERNAL
        source.start()
        titles()

        insert("Hidden")

        assertNoChange()
    }

    @Test
    fun aClientSelectionNarrowsTheRows() {
        insert("a")
        insert("b")

        query(ProviderTable.TASKS, "${Tasks.TITLE} = ?", arrayOf("b")).use { assertThat(it.count).isEqualTo(1) }
    }

    @Test
    fun anHourOldSnapshotIsRebuiltAgainstTheClock() {
        source.start()
        insert("Daily", due = Instant.parse("2026-10-10T09:00:00Z"), rrule = "FREQ=DAILY;COUNT=3")
        awaitChange()
        assertThat(currentOriginalTime()).isEqualTo(Instant.parse("2026-10-10T09:00:00Z").toEpochMilliseconds())

        clock.current += 1.days

        assertThat(currentOriginalTime()).isEqualTo(Instant.parse("2026-10-11T09:00:00Z").toEpochMilliseconds())
        awaitChange()
    }

    @Test
    fun aFreshSnapshotIsReused() {
        insert("a")
        titles()

        clock.current += 30.minutes

        query(ProviderTable.INSTANCES).use { assertThat(it.count).isEqualTo(1) }
        assertNoChange()
    }

    private fun currentOriginalTime(): Long =
        query(ProviderTable.INSTANCES, "${Instances.DISTANCE_FROM_CURRENT} = 0").use {
            assertThat(it.moveToFirst()).isTrue()
            it.getLong(it.getColumnIndexOrThrow(Instances.INSTANCE_ORIGINAL_TIME))
        }
}

private class MutableClock(var current: Instant) : Clock {
    override fun now(): Instant = current
}

private object NoProviderInstalled : ProviderEnvironment {
    override fun packageDeclaring(authority: String): String? = null
    override fun isGranted(permission: String): Boolean = false
    override fun appLabel(packageName: String): String? = null
}

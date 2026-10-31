package com.aiexile.agendula.data.tasks.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.data.tasks.TaskQuery
import com.aiexile.agendula.domain.TaskForm
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Auto Backup restore path, on disk.
 *
 * Auto Backup copies database files without checkpointing, and Room runs in WAL
 * mode — so `.db` alone can be a *stale* copy of a database whose recent writes
 * are still in the `-wal` sidecar. `res/xml/backup_rules.xml` carries all three
 * files and [DatabaseCheckpoint] truncates the log on `ON_STOP`; this asserts
 * that both of those actually do what they claim, and that neither alone is an
 * assumption.
 *
 * A file copy of a live database stands in for the backup transport — the
 * transport is what Auto Backup does to these files, and it is not what is under
 * test here.
 */
@RunWith(AndroidJUnit4::class)
class TasksDatabaseRestoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: TasksDatabase
    private lateinit var source: RoomTasksDataSource
    private var listId = 0L
    private var restored: TasksDatabase? = null

    @Before
    fun setUp() {
        delete(LIVE)
        delete(BACKUP)
        db = open(LIVE)
        source = RoomTasksDataSource(db)
        listId = source.createLocalList("Personal", 0xFF112233.toInt())
    }

    @After
    fun tearDown() {
        restored?.close()
        db.close()
        delete(LIVE)
        delete(BACKUP)
    }

    @Test
    fun roomRunsInWalMode() {
        // Everything below is only interesting because of this.
        assertThat(journalMode()).isEqualTo("wal")
    }

    @Test
    fun aBackupOfTheDbFileAloneLosesWhateverIsStillInTheWal() {
        write("checkpointed")
        checkpoint()
        write("only in the wal")

        backUp(withSidecars = false)

        assertThat(restore()).containsExactly("checkpointed")
    }

    @Test
    fun aBackupThatCarriesTheSidecarsKeepsTheLastWrite() {
        write("checkpointed")
        checkpoint()
        write("only in the wal")

        backUp(withSidecars = true)

        assertThat(restore()).containsExactly("checkpointed", "only in the wal")
    }

    @Test
    fun checkpointingFirstMakesTheDbFileAloneEnough() {
        write("checkpointed")
        checkpoint()
        write("last write")

        // What DatabaseCheckpoint runs on ON_STOP — the fallback for a restore
        // that arrives without the sidecars.
        checkpoint()
        backUp(withSidecars = false)

        assertThat(restore()).containsExactly("checkpointed", "last write")
    }

    // --- the moving parts -----------------------------------------------------

    private fun open(name: String): TasksDatabase =
        Room.databaseBuilder(context, TasksDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    private fun write(title: String) {
        source.insertTask(TaskForm(title = title, listId = listId))
    }

    private fun journalMode(): String =
        db.openHelper.writableDatabase.query("PRAGMA journal_mode").use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0).lowercase()
        }

    /** [DatabaseCheckpoint]'s pragma, asserting it was not blocked by a reader. */
    private fun checkpoint() {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    /** Copies the live database the way Auto Backup would: no checkpoint, files as they lie. */
    private fun backUp(withSidecars: Boolean) {
        delete(BACKUP)
        val live = context.getDatabasePath(LIVE)
        val backup = context.getDatabasePath(BACKUP)
        live.copyTo(backup, overwrite = true)
        if (!withSidecars) return
        SIDECARS.forEach { suffix ->
            val from = File(live.path + suffix)
            if (from.exists()) from.copyTo(File(backup.path + suffix), overwrite = true)
        }
    }

    /** Opens the copy as a fresh install would and reports the task titles that survived. */
    private fun restore(): List<String> {
        restored?.close()
        val database = open(BACKUP).also { restored = it }
        return RoomTasksDataSource(database).tasks(TaskQuery(includeCompleted = true)).map { it.title }
    }

    private fun delete(name: String) {
        val base = context.getDatabasePath(name)
        base.delete()
        SIDECARS.forEach { File(base.path + it).delete() }
    }

    private companion object {
        const val LIVE = "restore-live.db"
        const val BACKUP = "restore-backup.db"
        val SIDECARS = listOf("-wal", "-shm")
    }
}

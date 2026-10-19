package de.jeanlucmakiola.agendula.data.tasks.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Migrations, proven against the committed schemas in `app/schemas/`. */
@RunWith(AndroidJUnit4::class)
class TasksDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TasksDatabase::class.java,
    )

    @Test
    fun buildsV1FromTheExportedSchema() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO task_lists (id, name, color) VALUES (1, 'Groceries', 0)")
            db.execSQL("INSERT INTO tasks (id, list_id, uid, title) VALUES (1, 1, 'uid-1', 'Buy milk')")

            db.query("SELECT title FROM tasks").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("Buy milk")
            }
        }
    }

    @Test
    fun validatesV1AgainstTheExportedSchema() {
        helper.createDatabase(TEST_DB, 1).close()

        // No migrations to run: v1 is opened and checked against 1.json, which is
        // what proves the harness rather than the schema.
        helper.runMigrationsAndValidate(TEST_DB, 1, true).use { db ->
            assertThat(db.version).isEqualTo(1)
        }
    }

    @Test
    fun liftsCategoriesOutOfTheResidueWithoutDirtyingTheTask() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO task_lists (id, name, color) VALUES (1, 'Work', 0)")
            db.execSQL(
                "INSERT INTO tasks (id, list_id, uid, title, unknown_properties) VALUES (?, 1, ?, ?, ?)",
                arrayOf<Any?>(1, "uid-1", "Claimed", "X-FOO:bar\r\nCATEGORIES:Work,Home\r\n"),
            )
            db.execSQL(
                "INSERT INTO tasks (id, list_id, uid, title, unknown_properties) VALUES (?, 1, ?, ?, ?)",
                arrayOf<Any?>(2, "uid-2", "Split", "CATEGORIES:a\r\nCATEGORIES:b\r\n"),
            )
            db.execSQL("INSERT INTO tasks (id, list_id, uid, title) VALUES (3, 1, 'uid-3', 'Untagged')")
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, TasksDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT categories, unknown_properties, is_dirty FROM tasks ORDER BY id").use { c ->
                c.moveToNext()
                assertThat(c.getString(0)).isEqualTo("Work,Home")
                assertThat(c.getString(1)).doesNotContain("CATEGORIES")
                assertThat(c.getString(1)).contains("X-FOO:bar")
                assertThat(c.getInt(2)).isEqualTo(0)

                c.moveToNext()
                assertThat(c.getString(0)).isEqualTo("a,b")
                assertThat(c.getString(1)).isEqualTo("CATEGORIES:a\r\nCATEGORIES:b\r\n")

                c.moveToNext()
                assertThat(c.getString(0)).isEmpty()
            }
        }
    }

    @Test
    fun liftsClaimableAlarmsAndPushesRemindersThatNeverLeftTheDevice() {
        val future = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
        val past = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val claimable = "X-FOO:bar\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nX-WR-ALARMUID:srv\r\n" +
            "TRIGGER;RELATED=END:-PT15M\r\nEND:VALARM\r\n"
        val appleDefault = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nX-APPLE-DEFAULT-ALARM:TRUE\r\n" +
            "TRIGGER;RELATED=END:-PT30M\r\nEND:VALARM\r\n"
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL("INSERT INTO accounts (id, display_name) VALUES (1, 'me')")
            db.execSQL("INSERT INTO task_lists (id, name, color, account_id) VALUES (1, 'Synced', 0, 1)")
            db.execSQL("INSERT INTO task_lists (id, name, color) VALUES (2, 'Local', 0)")
            db.execSQL("INSERT INTO task_lists (id, name, color, account_id, is_read_only) VALUES (3, 'Shared', 0, 1, 1)")
            fun task(id: Int, list: Int, due: Long, residue: String? = null) = db.execSQL(
                "INSERT INTO tasks (id, list_id, uid, title, due, unknown_properties) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(id, list, "uid-$id", "Task $id", due, residue),
            )
            fun alarm(task: Int, minutes: Int) =
                db.execSQL("INSERT INTO task_alarms (task_id, minutes_before) VALUES (?, ?)", arrayOf<Any?>(task, minutes))
            // A server alarm, and the same lead copied locally by a fork.
            task(1, 1, future, claimable)
            alarm(1, 15)
            // Set in Agendula, never uploaded — still to fire, already past, device-only.
            task(2, 1, future)
            alarm(2, 30)
            task(3, 1, past)
            alarm(3, 30)
            task(4, 2, future)
            alarm(4, 30)
            // Somebody else's default alarm: not ours to claim.
            task(5, 1, future, appleDefault)
            // A read-only share: dirty would freeze it, never sent and never refreshed.
            task(6, 3, future)
            alarm(6, 30)
        }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, TasksDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT task_id, minutes_before, uid, raw FROM task_alarms ORDER BY task_id").use { c ->
                val rows = buildList { while (c.moveToNext()) add(listOf(c.getLong(0), c.getInt(1), c.getString(2), c.getString(3))) }
                // One for task 1: the lifted copy won and the local duplicate went.
                assertThat(rows.map { it[0] }).containsExactly(1L, 2L, 3L, 4L, 6L).inOrder()
                assertThat(rows[0][2]).isEqualTo("srv")
                assertThat(rows[0][3] as String).contains("TRIGGER;RELATED=END:-PT15M")
                // Every pre-existing row got an identity of its own, shaped as a UUID.
                assertThat(rows.drop(1).map { it[2] as String }.toSet()).hasSize(4)
                rows.drop(1).forEach {
                    assertThat(it[2] as String).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                }
                assertThat(rows.drop(1).all { (it[2] as String).isNotEmpty() && it[3] == null }).isTrue()
            }
            db.query("SELECT id, unknown_properties, is_dirty, last_modified, reminders_off FROM tasks ORDER BY id").use { c ->
                c.moveToNext()
                assertThat(c.getString(1)).isEqualTo("X-FOO:bar\r\n")
                assertThat(c.getInt(2)).isEqualTo(0)
                assertThat(c.getInt(4)).isEqualTo(0)
                c.moveToNext()
                assertThat(c.getInt(2)).isEqualTo(1)
                // The body changes, so its modification stamp does too.
                assertThat(c.isNull(3)).isFalse()
                c.moveToNext()
                assertThat(c.getInt(2)).isEqualTo(0)
                c.moveToNext()
                assertThat(c.getInt(2)).isEqualTo(0)
                c.moveToNext()
                assertThat(c.getString(1)).contains("X-APPLE-DEFAULT-ALARM:TRUE")
                assertThat(c.getInt(2)).isEqualTo(0)
                c.moveToNext()
                assertThat(c.getInt(2)).isEqualTo(0)
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}

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

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}

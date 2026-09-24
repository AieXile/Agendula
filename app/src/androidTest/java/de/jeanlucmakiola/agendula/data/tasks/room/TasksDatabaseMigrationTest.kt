package de.jeanlucmakiola.agendula.data.tasks.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration harness, proven against the committed schema in `app/schemas/`.
 *
 * There is one schema version today, so all there is to assert is that the helper
 * can build v1 from the exported JSON, seed it, and validate it back — i.e. the
 * export, the assets wiring and the identity hash all line up. That is the point:
 * the first real migration only has to add its own case.
 *
 * **Adding a v1 → v2 case.** When sync adds columns, bump [TasksDatabase]'s
 * `version`, let KSP export `2.json`, declare the `Migration(1, 2)` next to the
 * database, and add a test here shaped like this:
 *
 * ```
 * helper.createDatabase(TEST_DB, 1).use { db ->
 *     db.execSQL("INSERT INTO task_lists (name, color) VALUES ('Groceries', 0)")
 * }
 * helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
 *     // read the seeded rows back — validation proves the shape, not the data
 * }
 * ```
 */
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

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}

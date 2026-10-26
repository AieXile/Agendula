package de.jeanlucmakiola.agendula.data.tasks.provider

import android.database.Cursor
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Lists
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Tasks
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The SQL a client sends runs against the snapshot as it would against OpenTasks. */
@RunWith(AndroidJUnit4::class)
class SnapshotDatabaseTest {

    private val db = SnapshotDatabase()

    private fun task(id: Long, title: String?, due: Long? = null): ProviderRow =
        mapOf(Tasks.ID to id, Tasks.LIST_ID to 1L, Tasks.TITLE to title, Tasks.DUE to due, Tasks.PRIORITY to 0)

    private fun load(vararg tasks: ProviderRow, lists: List<ProviderRow> = emptyList()) = db.load(
        ProviderSnapshot(
            ProviderTable.entries.associateWith {
                when (it) {
                    ProviderTable.TASKS -> tasks.toList()
                    ProviderTable.LISTS -> lists
                    else -> emptyList()
                }
            },
        ),
    )

    private fun Cursor.strings(column: String): List<String?> = use {
        val index = getColumnIndexOrThrow(column)
        buildList { while (moveToNext()) add(if (isNull(index)) null else getString(index)) }
    }

    @Test
    fun aNullProjectionReturnsEveryColumnInTableOrder() {
        load(task(1, "a"))

        db.query(ProviderTable.TASKS, null, null, null, null).use {
            assertThat(it.columnNames.toList()).containsExactlyElementsIn(ProviderTable.TASKS.columns.map { c -> c.first })
                .inOrder()
            assertThat(it.count).isEqualTo(1)
        }
    }

    @Test
    fun selectionArgumentsAndSortOrderApply() {
        load(task(1, "b", due = 30), task(2, "a", due = 10), task(3, "c", due = 20), task(4, "skip", due = 5))

        val titles = db.query(ProviderTable.TASKS, arrayOf(Tasks.TITLE), "${Tasks.TITLE} != ?", arrayOf("skip"), "${Tasks.DUE} ASC")
            .strings(Tasks.TITLE)

        assertThat(titles).containsExactly("a", "c", "b").inOrder()
    }

    @Test
    fun typesSurviveTheCopy() {
        load(task(1, null, due = 1_700_000_000_000))

        db.query(ProviderTable.TASKS, arrayOf(Tasks.TITLE, Tasks.DUE, Tasks.PRIORITY), null, null, null).use {
            it.moveToFirst()
            assertThat(it.isNull(0)).isTrue()
            assertThat(it.getType(1)).isEqualTo(Cursor.FIELD_TYPE_INTEGER)
            assertThat(it.getLong(1)).isEqualTo(1_700_000_000_000)
            assertThat(it.getInt(2)).isEqualTo(0)
        }
    }

    @Test
    fun anUnsupportedColumnReadsAsNull() {
        load(task(1, "a"))

        assertThat(db.query(ProviderTable.TASKS, arrayOf("_sync_id"), null, null, null).strings("_sync_id"))
            .containsExactly(null)
    }

    @Test
    fun anUnknownProjectionColumnIsRejected() {
        load(task(1, "a"))

        assertThrows(IllegalArgumentException::class.java) {
            db.query(ProviderTable.TASKS, arrayOf("no_such_column"), null, null, null)
        }
    }

    @Test
    fun anUnknownSelectionColumnIsRejected() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        load(task(1, "a"))

        assertThrows(IllegalArgumentException::class.java) {
            db.query(ProviderTable.TASKS, null, "sqlite_master.name IS NOT NULL", null, null)
        }
    }

    @Test
    fun aSelectionCannotBreakOutOfItsWhereClause() {
        load(task(1, "a"))

        assertThrows(IllegalArgumentException::class.java) {
            db.query(ProviderTable.TASKS, null, "1) UNION SELECT name FROM sqlite_master --", null, null)
        }
    }

    @Test
    fun eachTableAnswersOnlyForItsOwnRows() {
        load(task(1, "a"), lists = listOf(mapOf(Lists.ID to 7L, Lists.NAME to "Home")))

        assertThat(db.query(ProviderTable.LISTS, arrayOf(Lists.NAME), null, null, null).strings(Lists.NAME))
            .containsExactly("Home")
        db.query(ProviderTable.INSTANCES, null, null, null, null).use { assertThat(it.count).isEqualTo(0) }
    }

    @Test
    fun aReloadReplacesTheRowsButNotAnOpenCursor() {
        load(task(1, "old"))
        val before = db.query(ProviderTable.TASKS, arrayOf(Tasks.TITLE), null, null, null)

        load(task(2, "new"))

        assertThat(before.strings(Tasks.TITLE)).containsExactly("old")
        assertThat(db.query(ProviderTable.TASKS, arrayOf(Tasks.TITLE), null, null, null).strings(Tasks.TITLE))
            .containsExactly("new")
    }

    @Test
    fun anEmptySnapshotEmptiesEveryTable() {
        load(task(1, "a"))

        db.load(ProviderSnapshot.EMPTY)

        ProviderTable.entries.forEach { table ->
            db.query(table, null, null, null, null).use { assertThat(it.count).isEqualTo(0) }
        }
    }
}

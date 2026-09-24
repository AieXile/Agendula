package de.jeanlucmakiola.agendula.data.tasks.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.data.tasks.TaskQuery
import de.jeanlucmakiola.agendula.domain.TaskForm
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/**
 * The plan's shape at scale: 5,000 tasks with 20 recurring series, read the way a
 * smart list reads them — one `tasks(TaskQuery(includeCompleted = true))`, which
 * includes expanding every series in memory.
 *
 * The assertion is a deliberately loose ceiling, so it catches a real regression
 * rather than CI jitter; the printed numbers are what the check is actually for.
 */
@RunWith(AndroidJUnit4::class)
class TasksDatabasePerformanceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: TasksDatabase
    private lateinit var source: RoomTasksDataSource
    private var listId = 0L

    @Before
    fun setUp() {
        delete()
        db = Room.databaseBuilder(context, TasksDatabase::class.java, DB)
            .allowMainThreadQueries()
            .build()
        source = RoomTasksDataSource(db)
        listId = source.createLocalList("Everything", 0xFF112233.toInt())
    }

    @After
    fun tearDown() {
        db.close()
        delete()
    }

    @Test
    fun readsFiveThousandTasksWithTwentySeriesInsideTheBudget() {
        val seeded = measureTime { seed() }

        // Discard the first read: it pays for statement compilation and page cache
        // warming, which a running app has already paid.
        source.tasks(TaskQuery(includeCompleted = true))
        val (tasks, elapsed) = measureTimedValue {
            source.tasks(TaskQuery(includeCompleted = true))
        }

        println(
            "[perf] $TASK_COUNT tasks / $SERIES_COUNT series -> ${tasks.size} occurrences " +
                "in $elapsed (seed $seeded)",
        )
        // Expansion is bounded twice over: the read window is 1 year back and 2
        // forward, and each series stops at ExpansionWindow.maxOccurrences (500),
        // so the occurrence count cannot grow with the age of the series.
        assertThat(tasks.size).isAtLeast(TASK_COUNT)
        assertThat(elapsed.inWholeMilliseconds).isLessThan(CEILING_MILLIS)
    }

    private fun seed() {
        val anchor = Clock.System.now() - 30.days
        val ids = ArrayList<Long>(TASK_COUNT)
        db.runInTransaction {
            repeat(TASK_COUNT) { index ->
                ids += source.insertTask(
                    TaskForm(title = "Task $index", listId = listId, due = anchor + index.days),
                )
            }
        }
        db.runInTransaction {
            ids.take(SERIES_COUNT).forEach { id ->
                val entity = db.tasks().entity(id)!!
                db.tasks().update(
                    entity.copy(dtstart = anchor, due = anchor + 1.days, rrule = "FREQ=DAILY"),
                )
            }
        }
    }

    private fun delete() {
        val base = context.getDatabasePath(DB)
        base.delete()
        listOf("-wal", "-shm").forEach { File(base.path + it).delete() }
    }

    private companion object {
        const val DB = "performance-test.db"
        const val TASK_COUNT = 5_000
        const val SERIES_COUNT = 20
        const val CEILING_MILLIS = 8_000L
    }
}

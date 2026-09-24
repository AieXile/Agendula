package de.jeanlucmakiola.agendula.data.tasks.room

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.TaskForm
import de.jeanlucmakiola.agendula.domain.TaskStatus
import org.junit.jupiter.api.Test
import kotlin.time.Instant

private val NOW = Instant.fromEpochMilliseconds(1_768_467_600_000)
private const val ZONE = "Europe/Berlin"

private fun task(
    status: TaskStatus = TaskStatus.NEEDS_ACTION,
    percentComplete: Int? = null,
    completedAt: Instant? = null,
) = TaskEntity(
    id = 1,
    listId = 1,
    uid = "uid-1",
    status = status,
    percentComplete = percentComplete,
    completedAt = completedAt,
)

private fun form(
    title: String = "task",
    percentComplete: Int? = null,
    start: Instant? = null,
    due: Instant? = null,
    isAllDay: Boolean = false,
) = TaskForm(
    title = title,
    listId = 1,
    percentComplete = percentComplete,
    start = start,
    due = due,
    isAllDay = isAllDay,
)

class TaskFormWriterTest {

    @Test
    fun `mints the task with its uid and creation time`() {
        val entity = TaskFormWriter.newTask(form(title = "  Buy milk "), "uid-9", NOW, ZONE)

        assertThat(entity.uid).isEqualTo("uid-9")
        assertThat(entity.title).isEqualTo("Buy milk")
        assertThat(entity.createdAt).isEqualTo(NOW)
        assertThat(entity.lastModified).isEqualTo(NOW)
        assertThat(entity.isDirty).isTrue()
    }

    @Test
    fun `progress and status move together in both directions`() {
        assertThat(TaskFormWriter.apply(task(), form(percentComplete = 100), NOW, ZONE).status)
            .isEqualTo(TaskStatus.COMPLETED)
        assertThat(TaskFormWriter.apply(task(), form(percentComplete = 40), NOW, ZONE).status)
            .isEqualTo(TaskStatus.IN_PROCESS)
        assertThat(TaskFormWriter.apply(task(), form(percentComplete = 0), NOW, ZONE).status)
            .isEqualTo(TaskStatus.NEEDS_ACTION)
    }

    @Test
    fun `dropping below 100 percent reopens the task`() {
        // The provider auto-completed at 100% but would not reopen below it, which
        // stranded a task "done at 75%". TaskWriteMapper works around that for
        // External mode; on our own store the rule is simply symmetric.
        val completed = task(status = TaskStatus.COMPLETED, percentComplete = 100, completedAt = NOW)

        val reopened = TaskFormWriter.apply(completed, form(percentComplete = 75), NOW, ZONE)

        assertThat(reopened.status).isEqualTo(TaskStatus.IN_PROCESS)
        assertThat(reopened.completedAt).isNull()
    }

    @Test
    fun `a form with no percent leaves the completion state alone`() {
        val completed = task(status = TaskStatus.COMPLETED, percentComplete = 100, completedAt = NOW)

        val saved = TaskFormWriter.apply(completed, form(title = "renamed"), NOW, ZONE)

        assertThat(saved.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(saved.completedAt).isEqualTo(NOW)
    }

    @Test
    fun `re-saving a finished task keeps its original completion time`() {
        val earlier = Instant.fromEpochMilliseconds(1_000_000)
        val completed = task(status = TaskStatus.COMPLETED, percentComplete = 100, completedAt = earlier)

        val saved = TaskFormWriter.apply(completed, form(percentComplete = 100), NOW, ZONE)

        assertThat(saved.completedAt).isEqualTo(earlier)
    }

    @Test
    fun `all-day times are pinned to UTC midnight`() {
        // Date-only in iCalendar. Storing a local-midnight instant would land on the
        // previous day for anyone west of UTC.
        val midMorning = Instant.fromEpochMilliseconds(1_768_467_600_000)

        val saved = TaskFormWriter.apply(
            task(),
            form(start = midMorning, due = midMorning, isAllDay = true),
            NOW,
            ZONE,
        )

        assertThat(saved.dtstart!!.toEpochMilliseconds() % (24L * 60 * 60 * 1000)).isEqualTo(0)
        assertThat(saved.due!!.toEpochMilliseconds() % (24L * 60 * 60 * 1000)).isEqualTo(0)
        assertThat(saved.timezone).isNull()
    }

    @Test
    fun `a timed task records the zone, an undated one does not`() {
        val timed = TaskFormWriter.apply(task(), form(due = NOW), NOW, ZONE)
        assertThat(timed.timezone).isEqualTo(ZONE)

        val undated = TaskFormWriter.apply(task(), form(), NOW, ZONE)
        assertThat(undated.timezone).isNull()
    }

    @Test
    fun `writing a due date clears any duration`() {
        // RFC 5545 §3.6.2: DUE and DURATION are mutually exclusive.
        val withDuration = task().copy(duration = "PT1H")

        assertThat(TaskFormWriter.apply(withDuration, form(due = NOW), NOW, ZONE).duration).isNull()
    }

    @Test
    fun `the complete toggle sets and clears the whole triple`() {
        val done = TaskFormWriter.completed(task(), completed = true, now = NOW)
        assertThat(done.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(done.percentComplete).isEqualTo(100)
        assertThat(done.completedAt).isEqualTo(NOW)

        val reopened = TaskFormWriter.completed(done, completed = false, now = NOW)
        assertThat(reopened.status).isEqualTo(TaskStatus.NEEDS_ACTION)
        assertThat(reopened.percentComplete).isNull()
        assertThat(reopened.completedAt).isNull()
    }

    @Test
    fun `priority is written as the raw iCalendar integer`() {
        assertThat(TaskFormWriter.apply(task(), form().copy(priority = Priority.HIGH), NOW, ZONE).priority)
            .isEqualTo(1)
        assertThat(TaskFormWriter.apply(task(), form().copy(priority = Priority.NONE), NOW, ZONE).priority)
            .isEqualTo(0)
    }

    @Test
    fun `deleting one occurrence writes the exception onto the master`() {
        val master = task().copy(rrule = "FREQ=DAILY", timezone = ZONE)
        val occurrence = Instant.parse("2026-03-01T09:00:00Z")

        val excepted = TaskFormWriter.excepting(master, occurrence, NOW, ZONE, dirty = true)

        // ⚠️ Dropping the override row un-overrides the occurrence; the RRULE
        // then regenerates it. Only an EXDATE takes it out of the set.
        assertThat(excepted.exdate).isEqualTo("20260301T090000Z")
        assertThat(excepted.isDirty).isTrue()
        assertThat(excepted.lastModified).isEqualTo(NOW)
    }

    @Test
    fun `a device-only list gets the exception without being made dirty`() {
        val master = task().copy(rrule = "FREQ=DAILY")
        val excepted = TaskFormWriter.excepting(
            master,
            Instant.parse("2026-03-01T09:00:00Z"),
            NOW,
            ZONE,
            dirty = false,
        )

        // The occurrence still has to be hidden — there is no tombstone doing it.
        assertThat(excepted.exdate).isNotNull()
        assertThat(excepted.isDirty).isFalse()
    }

    @Test
    fun `an exception is written in the shape the list already has`() {
        val allDay = task().copy(isAllDay = true, exdate = "20260228")
        val occurrence = Instant.parse("2026-03-01T00:00:00Z")

        // ⚠️ lib-recur parses the whole EXDATE list or none of it, so a UTC
        // date-time appended to a run of DATEs drops every exception the series
        // had, this one included.
        assertThat(TaskFormWriter.excepting(allDay, occurrence, NOW, ZONE, dirty = true).exdate)
            .isEqualTo("20260228,20260301")

        val floating = task().copy(timezone = ZONE, exdate = "20260228T100000")
        assertThat(
            TaskFormWriter.excepting(
                floating,
                Instant.parse("2026-03-01T09:00:00Z"),
                NOW,
                ZONE,
                dirty = true,
            ).exdate,
        ).isEqualTo("20260228T100000,20260301T100000")
    }

    @Test
    fun `an occurrence already excepted is not added twice`() {
        val master = task().copy(exdate = "20260301T090000Z")
        val same = TaskFormWriter.excepting(
            master,
            Instant.parse("2026-03-01T09:00:00Z"),
            NOW,
            ZONE,
            dirty = true,
        )
        assertThat(same).isSameInstanceAs(master)
    }
}

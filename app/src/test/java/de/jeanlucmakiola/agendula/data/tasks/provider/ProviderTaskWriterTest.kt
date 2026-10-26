package de.jeanlucmakiola.agendula.data.tasks.provider

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.data.tasks.TasksContract
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Instances
import de.jeanlucmakiola.agendula.data.tasks.TasksContract.Tasks
import de.jeanlucmakiola.agendula.data.tasks.room.TaskEntity
import de.jeanlucmakiola.agendula.domain.TaskStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ProviderTaskWriterTest {

    private val now = Instant.parse("2026-10-15T10:00:00Z")
    private val earlier = Instant.parse("2026-10-01T10:00:00Z")
    private val task = TaskEntity(id = 5, listId = 1, uid = "uid-5", title = "Old", lastModified = earlier)

    private fun patch(current: TaskEntity = task, vararg values: Pair<String, Any?>) =
        ProviderTaskWriter.patch(current, mapOf(*values), now, "Europe/Berlin")

    private fun millis(at: Instant) = at.toEpochMilliseconds()

    // --- column filtering ---------------------------------------------------------

    @Test
    fun `published read-only columns are dropped`() {
        val values = mapOf(Tasks.TITLE to "t", Tasks.LIST_NAME to "x", Tasks.IS_CLOSED to 1, "_sync_id" to "s")

        assertThat(ProviderTaskWriter.writable(ProviderTable.TASKS, values, WRITABLE_TASK_COLUMNS))
            .containsExactly(Tasks.TITLE, "t")
    }

    @Test
    fun `a column the table does not have is rejected`() {
        val error = assertThrows<IllegalArgumentException> {
            ProviderTaskWriter.writable(ProviderTable.TASKS, mapOf("href" to "x", "etag" to "y"), WRITABLE_TASK_COLUMNS)
        }
        assertThat(error).hasMessageThat().contains("etag, href")
    }

    @Test
    fun `instances accept every task column and their own`() {
        val values = mapOf<String, Any?>(Tasks.STATUS to 2, Instances.INSTANCE_START to 0L)

        assertThat(ProviderTaskWriter.writable(ProviderTable.INSTANCES, values, WRITABLE_TASK_COLUMNS))
            .containsExactly(Tasks.STATUS, 2)
    }

    // --- new tasks ----------------------------------------------------------------

    @Test
    fun `a new task needs a list`() {
        assertThrows<IllegalArgumentException> {
            ProviderTaskWriter.newTask(mapOf(Tasks.TITLE to "t"), "u", now, "UTC")
        }
    }

    @Test
    fun `a new task takes the client's uid or the minted one`() {
        val minted = ProviderTaskWriter.newTask(mapOf(Tasks.LIST_ID to 3L), "minted", now, "UTC")
        val own = ProviderTaskWriter.newTask(mapOf(Tasks.LIST_ID to 3L, Tasks.UID to " theirs "), "minted", now, "UTC")

        assertThat(minted.uid).isEqualTo("minted")
        assertThat(own.uid).isEqualTo("theirs")
        assertThat(own.listId).isEqualTo(3L)
        assertThat(own.createdAt).isEqualTo(now)
        assertThat(own.lastModified).isEqualTo(now)
        assertThat(own.isDirty).isTrue()
    }

    @Test
    fun `a new task may have no title`() {
        assertThat(ProviderTaskWriter.newTask(mapOf(Tasks.LIST_ID to 3L), "u", now, "UTC").title).isNull()
    }

    // --- content ------------------------------------------------------------------

    @Test
    fun `only the columns given change`() {
        val current = task.copy(description = "keep", priority = 3, location = "home")

        val edited = patch(current, Tasks.TITLE to "  New  ")

        assertThat(edited.title).isEqualTo("New")
        assertThat(edited.description).isEqualTo("keep")
        assertThat(edited.priority).isEqualTo(3)
        assertThat(edited.location).isEqualTo("home")
        assertThat(edited.lastModified).isEqualTo(now)
        assertThat(edited.isDirty).isTrue()
    }

    @Test
    fun `blank text clears and null clears`() {
        val current = task.copy(description = "d", url = "u")

        val edited = patch(current, Tasks.DESCRIPTION to "   ", Tasks.URL to null)

        assertThat(edited.description).isNull()
        assertThat(edited.url).isNull()
    }

    @Test
    fun `numbers arrive in whatever shape the client sent`() {
        val edited = patch(task, Tasks.PRIORITY to "2", Tasks.CLASSIFICATION to 1L, Tasks.SORTING to 7, Tasks.TASK_COLOR to -16711936)

        assertThat(edited.priority).isEqualTo(2)
        assertThat(edited.classification).isEqualTo(1)
        assertThat(edited.sortOrder).isEqualTo(7)
        assertThat(edited.color).isEqualTo(-16711936)
    }

    @Test
    fun `a null priority is no priority`() {
        assertThat(patch(task.copy(priority = 4), Tasks.PRIORITY to null).priority).isEqualTo(TasksContract.PRIORITY_NONE)
    }

    @Test
    fun `out of range values are rejected`() {
        assertThrows<IllegalArgumentException> { patch(task, Tasks.PRIORITY to 10) }
        assertThrows<IllegalArgumentException> { patch(task, Tasks.CLASSIFICATION to 3) }
        assertThrows<IllegalArgumentException> { patch(task, Tasks.PERCENT_COMPLETE to 101) }
        assertThrows<IllegalArgumentException> { patch(task, Tasks.STATUS to 4) }
        assertThrows<IllegalArgumentException> { patch(task, Tasks.PRIORITY to "high") }
    }

    @Test
    fun `the uid cannot change`() {
        assertThat(patch(task, Tasks.UID to "uid-5").uid).isEqualTo("uid-5")
        assertThrows<IllegalArgumentException> { patch(task, Tasks.UID to "other") }
    }

    @Test
    fun `a task cannot be its own parent`() {
        assertThrows<IllegalArgumentException> { patch(task, Tasks.PARENT_ID to 5L) }
        assertThat(patch(task, Tasks.PARENT_ID to 9L).parentId).isEqualTo(9L)
        assertThat(patch(task.copy(parentId = 9), Tasks.PARENT_ID to null).parentId).isNull()
    }

    @Test
    fun `a list can be changed but not cleared`() {
        assertThat(patch(task, Tasks.LIST_ID to 2L).listId).isEqualTo(2L)
        assertThrows<IllegalArgumentException> { patch(task, Tasks.LIST_ID to null) }
    }

    @Test
    fun `sync state is left alone`() {
        val synced = task.copy(href = "/t.ics", etag = "e1", sequence = 4, unknownProperties = "X-FOO:bar")

        val edited = patch(synced, Tasks.TITLE to "t")

        assertThat(edited.href).isEqualTo("/t.ics")
        assertThat(edited.etag).isEqualTo("e1")
        assertThat(edited.sequence).isEqualTo(4)
        assertThat(edited.unknownProperties).isEqualTo("X-FOO:bar")
    }

    // --- times --------------------------------------------------------------------

    @Test
    fun `a timed task takes the device zone unless it has one`() {
        val due = Instant.parse("2026-10-20T15:00:00Z")

        assertThat(patch(task, Tasks.DUE to millis(due)).timezone).isEqualTo("Europe/Berlin")
        assertThat(patch(task.copy(timezone = "UTC"), Tasks.DUE to millis(due)).timezone).isEqualTo("UTC")
        assertThat(patch(task, Tasks.DUE to millis(due), Tasks.TZ to "America/New_York").timezone)
            .isEqualTo("America/New_York")
    }

    @Test
    fun `a new timed task without a zone is floating`() {
        val due = millis(Instant.parse("2026-10-20T09:00:00Z"))

        assertThat(ProviderTaskWriter.newTask(mapOf(Tasks.LIST_ID to 3L, Tasks.DUE to due), "u", now, "UTC").timezone).isNull()
        assertThat(ProviderTaskWriter.newTask(mapOf(Tasks.LIST_ID to 3L, Tasks.DUE to due, Tasks.TZ to "Europe/Paris"), "u", now, "UTC").timezone)
            .isEqualTo("Europe/Paris")
    }

    @Test
    fun `an unknown zone is rejected`() {
        assertThrows<IllegalArgumentException> { patch(task, Tasks.DUE to 0L, Tasks.TZ to "Mars/Olympus") }
    }

    @Test
    fun `an all-day task is floored to UTC midnight and has no zone`() {
        val due = Instant.parse("2026-10-20T15:00:00Z")

        val edited = patch(task.copy(timezone = "UTC"), Tasks.DUE to millis(due), Tasks.IS_ALLDAY to 1)

        assertThat(edited.isAllDay).isTrue()
        assertThat(edited.due).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"))
        assertThat(edited.timezone).isNull()
    }

    @Test
    fun `due before start is rejected`() {
        assertThrows<IllegalArgumentException> {
            patch(task, Tasks.DTSTART to millis(now), Tasks.DUE to millis(now - 1.hours))
        }
    }

    @Test
    fun `setting a due date drops a duration`() {
        val edited = patch(task.copy(dtstart = now, duration = "PT1H"), Tasks.DUE to millis(now + 2.hours))

        assertThat(edited.duration).isNull()
        assertThat(edited.due).isEqualTo(now + 2.hours)
    }

    @Test
    fun `due and duration together are rejected`() {
        assertThrows<IllegalArgumentException> {
            patch(task, Tasks.DTSTART to millis(now), Tasks.DUE to millis(now + 1.hours), Tasks.DURATION to "PT1H")
        }
    }

    @Test
    fun `a duration must be an iCalendar one`() {
        assertThat(patch(task, Tasks.DTSTART to millis(now), Tasks.DURATION to "P1W").duration).isEqualTo("P1W")
        assertThat(patch(task, Tasks.DTSTART to millis(now), Tasks.DURATION to "PT1H30M").duration).isEqualTo("PT1H30M")
        assertThrows<IllegalArgumentException> { patch(task, Tasks.DURATION to "an hour") }
    }

    // --- recurrence ---------------------------------------------------------------

    @Test
    fun `a rule is stored without its property name`() {
        assertThat(patch(task, Tasks.DUE to millis(now), Tasks.RRULE to "RRULE:FREQ=WEEKLY").rrule).isEqualTo("FREQ=WEEKLY")
    }

    @Test
    fun `a malformed rule is rejected`() {
        assertThrows<IllegalArgumentException> { patch(task, Tasks.DUE to millis(now), Tasks.RRULE to "FREQ=SOMETIMES") }
    }

    @Test
    fun `a rule needs a date`() {
        assertThrows<IllegalArgumentException> { patch(task, Tasks.RRULE to "FREQ=DAILY") }
    }

    @Test
    fun `dropping the rule drops its exceptions`() {
        val series = task.copy(due = now, rrule = "FREQ=DAILY", exdate = "20261016T100000Z", rdate = "20261030T100000Z")

        val edited = patch(series, Tasks.RRULE to null)

        assertThat(edited.rrule).isNull()
        assertThat(edited.exdate).isNull()
        assertThat(edited.rdate).isNull()
    }

    @Test
    fun `an override carries no rule and stays in its series`() {
        val override = task.copy(masterId = 4, recurrenceId = now, parentId = 2)

        assertThrows<IllegalArgumentException> { patch(override, Tasks.RRULE to "FREQ=DAILY") }
        assertThat(patch(override, Tasks.RRULE to null, Tasks.RDATE to null, Tasks.EXDATE to "").rrule).isNull()
        assertThrows<IllegalArgumentException> { patch(override, Tasks.LIST_ID to 2L) }
        assertThrows<IllegalArgumentException> { patch(override, Tasks.PARENT_ID to 3L) }
        assertThat(patch(override, Tasks.LIST_ID to 1L, Tasks.PARENT_ID to 2L, Tasks.TITLE to "x").title).isEqualTo("x")
    }

    // --- completion ---------------------------------------------------------------

    @Test
    fun `completing sets progress and time`() {
        val edited = patch(task, Tasks.STATUS to TasksContract.STATUS_COMPLETED)

        assertThat(edited.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(edited.percentComplete).isEqualTo(100)
        assertThat(edited.completedAt).isEqualTo(now)
    }

    @Test
    fun `completing keeps the client's completion time`() {
        val edited = patch(task, Tasks.STATUS to TasksContract.STATUS_COMPLETED, Tasks.COMPLETED to millis(earlier))

        assertThat(edited.completedAt).isEqualTo(earlier)
    }

    @Test
    fun `reopening clears progress and time`() {
        val done = task.copy(status = TaskStatus.COMPLETED, percentComplete = 100, completedAt = earlier)

        val edited = patch(done, Tasks.STATUS to TasksContract.STATUS_NEEDS_ACTION)

        assertThat(edited.status).isEqualTo(TaskStatus.NEEDS_ACTION)
        assertThat(edited.percentComplete).isNull()
        assertThat(edited.completedAt).isNull()
    }

    @Test
    fun `cancelling keeps progress and clears the completion time`() {
        val edited = patch(task.copy(percentComplete = 40, completedAt = earlier), Tasks.STATUS to TasksContract.STATUS_CANCELLED)

        assertThat(edited.status).isEqualTo(TaskStatus.CANCELLED)
        assertThat(edited.percentComplete).isEqualTo(40)
        assertThat(edited.completedAt).isNull()
    }

    @Test
    fun `progress implies a status`() {
        assertThat(patch(task, Tasks.PERCENT_COMPLETE to 50).status).isEqualTo(TaskStatus.IN_PROCESS)
        assertThat(patch(task, Tasks.PERCENT_COMPLETE to 0).status).isEqualTo(TaskStatus.NEEDS_ACTION)
        val done = patch(task, Tasks.PERCENT_COMPLETE to 100)
        assertThat(done.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(done.completedAt).isEqualTo(now)
    }

    @Test
    fun `progress below 100 reopens a completed task`() {
        val done = task.copy(status = TaskStatus.COMPLETED, percentComplete = 100, completedAt = earlier)

        val edited = patch(done, Tasks.PERCENT_COMPLETE to 60)

        assertThat(edited.status).isEqualTo(TaskStatus.IN_PROCESS)
        assertThat(edited.completedAt).isNull()
    }

    @Test
    fun `a completion time alone completes and clearing it reopens`() {
        val done = patch(task, Tasks.COMPLETED to millis(earlier))
        assertThat(done.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(done.percentComplete).isEqualTo(100)
        assertThat(done.completedAt).isEqualTo(earlier)

        val reopened = patch(done, Tasks.COMPLETED to null)
        assertThat(reopened.status).isEqualTo(TaskStatus.NEEDS_ACTION)
        assertThat(reopened.completedAt).isNull()
    }

    @Test
    fun `a completed row written back with status open reopens it fully`() {
        val done = task.copy(status = TaskStatus.COMPLETED, percentComplete = 100, completedAt = earlier)

        val edited = patch(done, Tasks.STATUS to 0, Tasks.PERCENT_COMPLETE to 100, Tasks.COMPLETED to millis(earlier))

        assertThat(edited.status).isEqualTo(TaskStatus.NEEDS_ACTION)
        assertThat(edited.percentComplete).isNull()
        assertThat(edited.completedAt).isNull()
    }

    @Test
    fun `the changed completion column decides over unchanged ones written back`() {
        val completed = patch(task, Tasks.STATUS to 0, Tasks.PERCENT_COMPLETE to 100, Tasks.COMPLETED to null)
        assertThat(completed.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(completed.completedAt).isEqualTo(now)

        val done = task.copy(status = TaskStatus.COMPLETED, percentComplete = 100, completedAt = earlier)
        val reopened = patch(done, Tasks.STATUS to 2, Tasks.PERCENT_COMPLETE to 100, Tasks.COMPLETED to null)
        assertThat(reopened.status).isEqualTo(TaskStatus.NEEDS_ACTION)
        assertThat(reopened.percentComplete).isNull()
    }

    @Test
    fun `a write without completion columns leaves completion alone`() {
        val done = task.copy(status = TaskStatus.COMPLETED, percentComplete = 100, completedAt = earlier)

        val edited = patch(done, Tasks.TITLE to "t")

        assertThat(edited.status).isEqualTo(TaskStatus.COMPLETED)
        assertThat(edited.completedAt).isEqualTo(earlier)
    }

    // --- occurrences --------------------------------------------------------------

    @Test
    fun `an occurrence is completed, cancelled or reopened`() {
        assertThat(ProviderTaskWriter.occurrenceChange(mapOf(Tasks.STATUS to 2))).isEqualTo(OccurrenceChange.COMPLETE)
        assertThat(ProviderTaskWriter.occurrenceChange(mapOf(Tasks.STATUS to 3))).isEqualTo(OccurrenceChange.CANCEL)
        assertThat(ProviderTaskWriter.occurrenceChange(mapOf(Tasks.STATUS to 0))).isEqualTo(OccurrenceChange.REOPEN)
        assertThat(ProviderTaskWriter.occurrenceChange(mapOf(Tasks.PERCENT_COMPLETE to 100))).isEqualTo(OccurrenceChange.COMPLETE)
        assertThat(ProviderTaskWriter.occurrenceChange(mapOf(Tasks.PERCENT_COMPLETE to 0))).isEqualTo(OccurrenceChange.REOPEN)
        assertThat(ProviderTaskWriter.occurrenceChange(mapOf(Tasks.COMPLETED to 1L))).isEqualTo(OccurrenceChange.COMPLETE)
        assertThat(ProviderTaskWriter.occurrenceChange(mapOf(Tasks.COMPLETED to null))).isEqualTo(OccurrenceChange.REOPEN)
    }

    @Test
    fun `status wins over progress on an occurrence`() {
        assertThat(ProviderTaskWriter.occurrenceChange(mapOf(Tasks.STATUS to 2, Tasks.PERCENT_COMPLETE to 100)))
            .isEqualTo(OccurrenceChange.COMPLETE)
    }

    @Test
    fun `an occurrence has no partial progress`() {
        assertThrows<IllegalArgumentException> { ProviderTaskWriter.occurrenceChange(mapOf(Tasks.STATUS to 1)) }
        assertThrows<IllegalArgumentException> { ProviderTaskWriter.occurrenceChange(mapOf(Tasks.PERCENT_COMPLETE to 50)) }
    }

    @Test
    fun `values without completion say nothing about the occurrence`() {
        assertThat(ProviderTaskWriter.occurrenceChange(mapOf(Tasks.TITLE to "t"))).isNull()
    }

    @Test
    fun `flags accept the shapes clients send`() {
        listOf(1, 1L, true, "1", "true").forEach {
            assertThat(patch(task, Tasks.DUE to millis(now + 1.days), Tasks.IS_ALLDAY to it).isAllDay).isTrue()
        }
        listOf(0, false, "0", null).forEach {
            assertThat(patch(task.copy(isAllDay = true), Tasks.IS_ALLDAY to it).isAllDay).isFalse()
        }
        assertThrows<IllegalArgumentException> { patch(task, Tasks.IS_ALLDAY to "yes") }
    }

    // --- no-op writes -------------------------------------------------------------

    @Test
    fun `a write that changes nothing returns the row as it was`() {
        val current = task.copy(description = "d", priority = 3, isDirty = false)

        assertThat(patch(current)).isSameInstanceAs(current)
        assertThat(patch(current, Tasks.TITLE to "Old", Tasks.PRIORITY to 3L, Tasks.DESCRIPTION to "d"))
            .isSameInstanceAs(current)
        assertThat(patch(current, Tasks.TITLE to "New").isDirty).isTrue()
    }

    @Test
    fun `only columns that differ from the row count as changed`() {
        val due = Instant.parse("2026-10-20T15:00:00Z")
        val series = task.copy(due = due, timezone = "UTC", rrule = "FREQ=DAILY", status = TaskStatus.IN_PROCESS, percentComplete = 50)
        val readBack = mapOf(
            Tasks.TITLE to "Old", Tasks.DUE to millis(due), Tasks.DTSTART to null, Tasks.TZ to "UTC", Tasks.IS_ALLDAY to 0,
            Tasks.RRULE to "FREQ=DAILY", Tasks.RDATE to null, Tasks.EXDATE to null, Tasks.DURATION to null,
            Tasks.STATUS to 1, Tasks.PERCENT_COMPLETE to 50, Tasks.COMPLETED to null,
        )

        assertThat(ProviderTaskWriter.changedColumns(series, readBack, "Europe/Berlin")).isEmpty()
        assertThat(
            ProviderTaskWriter.changedColumns(
                series,
                readBack + (Tasks.DUE to millis(due + 1.hours)) + (Tasks.TITLE to "New") + (Tasks.STATUS to 2),
                "Europe/Berlin",
            ),
        ).containsExactly(Tasks.DUE, Tasks.TITLE, Tasks.STATUS)
    }

    @Test
    fun `a value that does not fit counts as changed`() {
        assertThat(ProviderTaskWriter.changedColumns(task, mapOf(Tasks.PRIORITY to 99), "UTC")).containsExactly(Tasks.PRIORITY)
    }

    @Test
    fun `a floating task stays floating`() {
        val floating = task.copy(due = now, timezone = null)

        assertThat(patch(floating, Tasks.TITLE to "t").timezone).isNull()
        assertThat(patch(floating, Tasks.DUE to millis(now + 1.hours)).timezone).isNull()
        assertThat(patch(floating, Tasks.TITLE to "Old")).isSameInstanceAs(floating)
    }

    @Test
    fun `an all-day task made timed takes the device zone`() {
        val allDay = task.copy(due = Instant.parse("2026-10-20T00:00:00Z"), isAllDay = true)

        val timed = patch(allDay, Tasks.IS_ALLDAY to 0, Tasks.DUE to millis(Instant.parse("2026-10-20T15:00:00Z")))

        assertThat(timed.timezone).isEqualTo("Europe/Berlin")
    }

    @Test
    fun `clearing every date clears the zone`() {
        assertThat(patch(task.copy(due = now, timezone = "UTC"), Tasks.DUE to null).timezone).isNull()
    }

    @Test
    fun `exception and extra dates must be iCalendar values of one kind`() {
        val series = task.copy(due = now, rrule = "FREQ=DAILY", timezone = "UTC")

        assertThat(patch(series, Tasks.EXDATE to "20261016T100000Z, 20261017T100000Z").exdate)
            .isEqualTo("20261016T100000Z,20261017T100000Z")
        assertThat(patch(series, Tasks.RDATE to "20261030").rdate).isEqualTo("20261030")
        assertThrows<IllegalArgumentException> { patch(series, Tasks.EXDATE to "tomorrow") }
        assertThrows<IllegalArgumentException> { patch(series, Tasks.EXDATE to "20261016,20261017T100000Z") }
        assertThrows<IllegalArgumentException> { patch(series, Tasks.RDATE to "20261016T100000Z,20261017T100000") }
    }

    @Test
    fun `stored dates written back as synced are kept`() {
        val series = task.copy(due = now, rrule = "FREQ=DAILY", rdate = "20261016T100000Z/PT1H", exdate = "20261016,20261017T100000Z")

        val edited = patch(series, Tasks.TITLE to "New", Tasks.RDATE to series.rdate, Tasks.EXDATE to series.exdate)

        assertThat(edited.rdate).isEqualTo(series.rdate)
        assertThat(edited.exdate).isEqualTo(series.exdate)
        assertThat(ProviderTaskWriter.changedColumns(series, mapOf(Tasks.RDATE to series.rdate), "UTC")).isEmpty()
    }
}

package de.jeanlucmakiola.agendula.data.tasks.room

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.domain.TaskForm
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class TaskFormWriterSeriesTest {

    private val now = Instant.parse("2026-09-23T10:00:00Z")
    private val anchor = Instant.parse("2026-09-01T09:00:00Z")
    private val master = TaskEntity(
        id = 7,
        listId = 1,
        uid = "series",
        title = "Water plants",
        dtstart = anchor,
        due = anchor + 1.hours,
        rrule = "FREQ=DAILY;COUNT=30",
        timezone = "Europe/Berlin",
    )

    @Test
    fun `ending a series before an occurrence swaps COUNT for UNTIL`() {
        val ended = TaskFormWriter.endedBefore(master, anchor + 10.days, now, "Europe/Berlin")

        assertThat(ended.rrule).isEqualTo("FREQ=DAILY;UNTIL=20260911T085959Z")
        assertThat(ended.isDirty).isTrue()
    }

    @Test
    fun `an all-day series ends on the day before, as a DATE`() {
        val allDay = master.copy(isAllDay = true, dtstart = Instant.parse("2026-09-01T00:00:00Z"), due = null)

        val ended = TaskFormWriter.endedBefore(allDay, Instant.parse("2026-09-11T00:00:00Z"), now, "Europe/Berlin")

        assertThat(ended.rrule).isEqualTo("FREQ=DAILY;UNTIL=20260910")
    }

    @Test
    fun `the continuing series only counts what is left`() {
        assertThat(TaskFormWriter.remainingCount("FREQ=DAILY;COUNT=30", 10)).isEqualTo("FREQ=DAILY;COUNT=20")
        assertThat(TaskFormWriter.remainingCount("FREQ=WEEKLY", 10)).isEqualTo("FREQ=WEEKLY")
    }

    @Test
    fun `moving one occurrence for the whole series moves the anchor by as much`() {
        val occurrenceStart = anchor + 5.days
        val form = TaskForm(
            title = "Water all plants",
            listId = 1,
            start = occurrenceStart + 2.hours,
            due = occurrenceStart + 3.hours,
            rrule = master.rrule,
        )

        val edited = TaskFormWriter.seriesEdit(master, occurrenceStart, occurrenceStart + 1.hours, form, now, "Europe/Berlin")

        assertThat(edited.title).isEqualTo("Water all plants")
        assertThat(edited.dtstart).isEqualTo(anchor + 2.hours)
        assertThat(edited.due).isEqualTo(anchor + 3.hours)
        assertThat(edited.rrule).isEqualTo(master.rrule)
    }

    @Test
    fun `dropping the rule ends the series and its exceptions`() {
        val withExceptions = master.copy(exdate = "20260905T090000Z")

        val plain = TaskFormWriter.withRule(withExceptions, null)

        assertThat(plain.rrule).isNull()
        assertThat(plain.exdate).isNull()
    }

    @Test
    fun `an override never takes a rule`() {
        val override = master.copy(id = 9, masterId = 7, rrule = null)

        assertThat(TaskFormWriter.withRule(override, "FREQ=DAILY").rrule).isNull()
    }

    @Test
    fun `a floating series ends on a floating UNTIL and drops later RDATEs`() {
        val floating = master.copy(timezone = null, rdate = "20260905T110000,20260920T110000")

        val ended = TaskFormWriter.endedBefore(floating, Instant.parse("2026-09-11T07:00:00Z"), now, "Europe/Berlin")

        assertThat(ended.rrule).isEqualTo("FREQ=DAILY;UNTIL=20260911T085959")
        assertThat(ended.rdate).isEqualTo("20260905T110000")
    }

    @Test
    fun `a series moved in time takes its exceptions along`() {
        val withException = master.copy(exdate = "20260905T090000Z")
        val occurrenceStart = anchor + 1.days
        val form = TaskForm(
            title = master.title!!,
            listId = 1,
            start = occurrenceStart + 1.hours,
            due = occurrenceStart + 2.hours,
            rrule = master.rrule,
        )

        val edited = TaskFormWriter.seriesEdit(withException, occurrenceStart, occurrenceStart + 1.hours, form, now, "Europe/Berlin")

        assertThat(edited.exdate).isEqualTo("20260905T100000Z")
    }

    @Test
    fun `editing the series through a completed occurrence leaves the series open`() {
        val occurrenceStart = anchor + 1.days
        val form = TaskForm(title = "x", listId = 1, start = occurrenceStart, due = occurrenceStart + 1.hours, percentComplete = 100, rrule = master.rrule)

        val edited = TaskFormWriter.seriesEdit(master, occurrenceStart, occurrenceStart + 1.hours, form, now, "Europe/Berlin")

        assertThat(edited.status).isEqualTo(master.status)
        assertThat(edited.completedAt).isNull()
    }
}

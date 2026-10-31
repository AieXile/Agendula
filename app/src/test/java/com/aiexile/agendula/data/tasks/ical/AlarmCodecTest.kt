package com.aiexile.agendula.data.tasks.ical

import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.data.tasks.room.AlarmReference
import com.aiexile.agendula.data.tasks.room.TaskAlarmEntity
import com.aiexile.agendula.data.tasks.room.TaskEntity
import com.aiexile.agendula.domain.allDayInstantOf
import com.aiexile.agendula.domain.ical.ICalComponent
import com.aiexile.agendula.domain.ical.ICalParser
import com.aiexile.agendula.domain.ical.ICalSerializer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class AlarmCodecTest {

    private val due = Instant.parse("2026-10-01T15:00:00Z")
    private val start = Instant.parse("2026-10-01T09:00:00Z")
    private val timed = TaskEntity(listId = 1, uid = "t", due = due)
    private val withStart = timed.copy(dtstart = start)
    private val allDay = TaskEntity(
        listId = 1,
        uid = "t",
        due = allDayInstantOf(LocalDate.of(2026, 10, 1)),
        isAllDay = true,
    )

    private fun alarm(vararg lines: String): ICalComponent =
        ICalParser.parse((listOf("BEGIN:VALARM") + lines + "END:VALARM").joinToString("\r\n"))

    private fun lead(minutes: Int, reference: AlarmReference = AlarmReference.DUE) = AlarmCodec.Lead(minutes, reference)

    @Nested
    inner class Claiming {

        @Test
        fun `a display alarm before due is claimed`() {
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER;RELATED=END:-PT15M"), timed))
                .isEqualTo(lead(15))
        }

        @Test
        fun `an audio alarm is claimed too, an email one is not`() {
            assertThat(AlarmCodec.resolve(alarm("ACTION:AUDIO", "TRIGGER;RELATED=END:-PT5M"), timed)).isEqualTo(lead(5))
            assertThat(AlarmCodec.resolve(alarm("ACTION:EMAIL", "TRIGGER;RELATED=END:-PT5M"), timed)).isNull()
            assertThat(AlarmCodec.resolve(alarm("TRIGGER;RELATED=END:-PT5M"), timed)).isNull()
        }

        @Test
        fun `alarms that are somebody else's to fire stay in the residue`() {
            val unclaimable = listOf(
                alarm("ACTION:DISPLAY", "X-APPLE-DEFAULT-ALARM:TRUE", "TRIGGER;RELATED=END:-PT15M"),
                alarm("ACTION:DISPLAY", "X-APPLE-PROXIMITY:ARRIVE", "TRIGGER;VALUE=DATE-TIME:19760401T005545Z"),
                alarm("ACTION:DISPLAY", "PROXIMITY:DEPART", "TRIGGER;RELATED=END:-PT15M"),
                alarm("ACTION:DISPLAY", "REPEAT:2", "DURATION:PT5M", "TRIGGER;RELATED=END:-PT15M"),
                alarm("ACTION:DISPLAY", "RELATED-TO;RELTYPE=SNOOZE:other", "TRIGGER;RELATED=END:-PT15M"),
                alarm("ACTION:DISPLAY", "TRIGGER;VALUE=DATE-TIME:19760401T005545Z"),
            )
            unclaimable.forEach { assertThat(AlarmCodec.resolve(it, timed)).isNull() }
        }

        @Test
        fun `a trigger after its anchor is not claimed, because the planner would fire it early`() {
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER;RELATED=END:PT10M"), timed)).isNull()
        }

        @Test
        fun `a lead in seconds is not one the planner can reproduce`() {
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER;RELATED=END:-PT90S"), timed)).isNull()
        }

        @Test
        fun `RELATED=END without a DUE has nothing to count from`() {
            val noDue = timed.copy(due = null, dtstart = start)
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER;RELATED=END:-PT15M"), noDue)).isNull()
        }

        @Test
        fun `an absolute UTC trigger becomes a lead before due`() {
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER;VALUE=DATE-TIME:20261001T143000Z"), timed))
                .isEqualTo(lead(30))
        }

        @Test
        fun `an absolute trigger on a recurring task has no single lead`() {
            val recurring = timed.copy(rrule = "FREQ=DAILY")
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER;VALUE=DATE-TIME:20261001T143000Z"), recurring))
                .isNull()
        }

        @Test
        fun `the identity is taken from X-WR-ALARMUID, then UID`() {
            val apple = AlarmCodec.fromVAlarm(alarm("ACTION:DISPLAY", "X-WR-ALARMUID:apple", "UID:rfc", "TRIGGER:-PT5M"), timed)
            val rfc = AlarmCodec.fromVAlarm(alarm("ACTION:DISPLAY", "UID:rfc", "TRIGGER:-PT5M"), timed)
            assertThat(apple?.uid).isEqualTo("apple")
            assertThat(rfc?.uid).isEqualTo("rfc")
        }
    }

    @Nested
    inner class Anchors {

        @Test
        fun `RELATED=START with a distinct DTSTART is a before-start reminder`() {
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER;RELATED=START:-PT10M"), withStart))
                .isEqualTo(lead(10, AlarmReference.START))
        }

        @Test
        fun `RELATED is START by default`() {
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER:-PT10M"), withStart))
                .isEqualTo(lead(10, AlarmReference.START))
        }

        @Test
        fun `START on a task whose start is its due is a before-due reminder`() {
            val same = timed.copy(dtstart = due)
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER:-PT10M"), same)).isEqualTo(lead(10))
        }

        @Test
        fun `START on a task with only a DUE is Apple's shape, and reads as before-due`() {
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER;RELATED=START:-PT30M"), timed))
                .isEqualTo(lead(30))
        }
    }

    @Nested
    inner class AllDay {

        @Test
        fun `a relative trigger counts back from the date's midnight`() {
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER;RELATED=END:-P1D"), allDay))
                .isEqualTo(lead(24 * 60))
            // 09:00 the day before.
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER;RELATED=END:-PT15H"), allDay))
                .isEqualTo(lead(15 * 60))
        }

        @Test
        fun `a time on the day itself is a time of day, not an after-due alarm`() {
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER;RELATED=END:PT9H"), allDay))
                .isEqualTo(lead(-9 * 60))
        }

        @Test
        fun `a trigger after the day is over is not claimed`() {
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER;RELATED=END:PT24H"), allDay)).isNull()
        }

        @Test
        fun `an absolute trigger counts back from the due date`() {
            // 09:00 Berlin the day before, as Apple writes it.
            assertThat(AlarmCodec.resolve(alarm("ACTION:DISPLAY", "TRIGGER;VALUE=DATE-TIME:20260930T070000Z"), allDay))
                .isEqualTo(lead(24 * 60))
        }

        @Test
        fun `an authored all-day alarm is written in days`() {
            val row = TaskAlarmEntity(taskId = 1, minutesBefore = 2 * 24 * 60, uid = "a")
            assertThat(AlarmCodec.toVAlarm(row, allDay, due)?.property("TRIGGER")?.value).isEqualTo("-P2D")
            val onTheDay = row.copy(minutesBefore = 0)
            assertThat(AlarmCodec.toVAlarm(onTheDay, allDay, due)?.property("TRIGGER")?.value).isEqualTo("PT0S")
        }

        @Test
        fun `an authored all-day alarm with a time of its own is written from midnight`() {
            val nine = TaskAlarmEntity(taskId = 1, minutesBefore = -9 * 60, uid = "a")
            assertThat(AlarmCodec.toVAlarm(nine, allDay, due)?.property("TRIGGER")?.value).isEqualTo("PT9H")
            val fivePmBefore = nine.copy(minutesBefore = 7 * 60)
            assertThat(AlarmCodec.toVAlarm(fivePmBefore, allDay, due)?.property("TRIGGER")?.value).isEqualTo("-PT7H")
        }
    }

    @Nested
    inner class Writing {

        private val earlier = due - 48.hours

        @Test
        fun `an authored alarm names itself both ways and counts back from due`() {
            val written = AlarmCodec.toVAlarm(TaskAlarmEntity(taskId = 1, minutesBefore = 15, uid = "a-1"), timed, earlier)!!
            assertThat(ICalSerializer.serialize(written)).isEqualTo(
                "BEGIN:VALARM\r\n" +
                    "UID:a-1\r\n" +
                    "X-WR-ALARMUID:a-1\r\n" +
                    "ACTION:DISPLAY\r\n" +
                    "DESCRIPTION:Reminder\r\n" +
                    "TRIGGER;RELATED=END:-PT15M\r\n" +
                    "END:VALARM\r\n",
            )
        }

        @Test
        fun `a before-start row is written against START`() {
            val row = TaskAlarmEntity(taskId = 1, minutesBefore = 10, reference = AlarmReference.START, uid = "a")
            val trigger = AlarmCodec.toVAlarm(row, withStart, earlier)?.property("TRIGGER")
            assertThat(trigger?.param("RELATED")).isEqualTo("START")
            assertThat(trigger?.value).isEqualTo("-PT10M")
        }

        @Test
        fun `a row whose anchor is gone is not written, so the resource stays valid`() {
            val row = TaskAlarmEntity(taskId = 1, minutesBefore = 15, uid = "a")
            assertThat(AlarmCodec.toVAlarm(row, timed.copy(due = null), earlier)).isNull()
        }

        @Test
        fun `an alarm long past is written acknowledged`() {
            val row = TaskAlarmEntity(taskId = 1, minutesBefore = 15, uid = "a")
            val muchLater = due + 7.hours
            assertThat(AlarmCodec.toVAlarm(row, timed, muchLater)?.property("ACKNOWLEDGED")?.value)
                .isEqualTo("20261001T220000Z")
            assertThat(AlarmCodec.toVAlarm(row, timed, earlier)?.property("ACKNOWLEDGED")).isNull()
        }

        @Test
        fun `one still inside the missed-reminder grace is not, or it would silence the late reminder here`() {
            val row = TaskAlarmEntity(taskId = 1, minutesBefore = 15, uid = "a")
            assertThat(AlarmCodec.toVAlarm(row, timed, due + 1.hours)?.property("ACKNOWLEDGED")).isNull()
        }

        @Test
        fun `a before-start row on a task that lost its start goes out before due`() {
            val row = TaskAlarmEntity(taskId = 1, minutesBefore = 10, reference = AlarmReference.START, uid = "a")
            val trigger = AlarmCodec.toVAlarm(row, timed, earlier)?.property("TRIGGER")
            assertThat(trigger?.param("RELATED")).isEqualTo("END")
            assertThat(trigger?.value).isEqualTo("-PT10M")
        }

        @Test
        fun `a before-due row on a task without a due goes out before start`() {
            val row = TaskAlarmEntity(taskId = 1, minutesBefore = 10, uid = "a")
            val trigger = AlarmCodec.toVAlarm(row, withStart.copy(due = null), earlier)?.property("TRIGGER")
            assertThat(trigger?.param("RELATED")).isEqualTo("START")
            assertThat(trigger?.value).isEqualTo("-PT10M")
        }

        @Test
        fun `an all-day row kept in whole days by an earlier build keeps the original's time`() {
            val row = TaskAlarmEntity(
                taskId = 1,
                minutesBefore = 0,
                uid = "a",
                raw = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;RELATED=END:PT9H\r\nEND:VALARM\r\n",
            )
            assertThat(AlarmCodec.toVAlarm(row, allDay, earlier)?.property("TRIGGER")?.value).isEqualTo("PT9H")
        }

        @Test
        fun `the wire lead is what the row reads back as`() {
            val afterDue = TaskAlarmEntity(taskId = 1, minutesBefore = -15, uid = "a")
            assertThat(AlarmCodec.wireLead(afterDue, timed)).isEqualTo(lead(0))
            assertThat(AlarmCodec.wireLead(afterDue, allDay)).isEqualTo(lead(-15))
        }

        @Test
        fun `a row rounded by an earlier build does not claim the original's time as its own`() {
            val row = TaskAlarmEntity(
                taskId = 1,
                minutesBefore = 0,
                uid = "a",
                raw = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;RELATED=END:PT9H\r\nEND:VALARM\r\n",
            )
            assertThat(AlarmCodec.wireLead(row, allDay)).isEqualTo(lead(0))
        }
    }

    @Nested
    inner class Unchanged {

        private fun claimed(text: String, task: TaskEntity) =
            checkNotNull(AlarmCodec.fromVAlarm(alarm(*text.split("|").toTypedArray()), task))

        @Test
        fun `an unchanged row is written back verbatim, whatever spelling it arrived in`() {
            val row = claimed("ACTION:DISPLAY|TRIGGER;RELATED=END:-P0DT0H15M0S|X-VENDOR:keep", timed)
            assertThat(row.minutesBefore).isEqualTo(15)
            val written = AlarmCodec.toVAlarm(row, timed, due)!!
            assertThat(written.property("TRIGGER")?.value).isEqualTo("-P0DT0H15M0S")
            assertThat(written.property("X-VENDOR")?.value).isEqualTo("keep")
        }

        @Test
        fun `a relative original survives a due-date edit`() {
            val row = claimed("ACTION:DISPLAY|TRIGGER;RELATED=END:-PT15M|X-VENDOR:keep", timed)
            val moved = timed.copy(due = due + 24.hours)
            assertThat(AlarmCodec.toVAlarm(row, moved, due)?.property("X-VENDOR")).isNotNull()
        }

        @Test
        fun `an absolute original stops matching once due moves, and the row is authored fresh`() {
            val row = claimed("ACTION:DISPLAY|TRIGGER;VALUE=DATE-TIME:20261001T143000Z|X-WR-ALARMUID:abs", timed)
            val moved = timed.copy(due = due + 24.hours)
            val written = AlarmCodec.toVAlarm(row, moved, due)!!
            assertThat(written.property("TRIGGER")?.value).isEqualTo("-PT30M")
            // Under its old identity, so other clients see it change, not appear.
            assertThat(written.property("X-WR-ALARMUID")?.value).isEqualTo("abs")
        }

        @Test
        fun `an edited lead is authored fresh`() {
            val row = claimed("ACTION:DISPLAY|TRIGGER;RELATED=END:-PT15M|X-VENDOR:keep", timed).copy(minutesBefore = 60)
            val written = AlarmCodec.toVAlarm(row, timed, due)!!
            assertThat(written.property("TRIGGER")?.value).isEqualTo("-PT1H")
            assertThat(written.property("X-VENDOR")).isNull()
        }
    }
}

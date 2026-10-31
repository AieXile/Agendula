package com.aiexile.agendula.data.tasks.ical

import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.data.tasks.room.AlarmReference
import com.aiexile.agendula.data.tasks.room.TaskAlarmEntity
import com.aiexile.agendula.data.tasks.room.TaskEntity
import com.aiexile.agendula.domain.PRIORITY_NONE
import com.aiexile.agendula.domain.TaskStatus
import com.aiexile.agendula.domain.ical.ICalComponent
import com.aiexile.agendula.domain.ical.ICalParser
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class VTodoMapperTest {

    private fun vtodo(vararg lines: String): ICalComponent =
        ICalParser.parse((listOf("BEGIN:VTODO", "UID:t-1") + lines + "END:VTODO").joinToString("\r\n"))

    @Nested
    inner class Reading {

        @Test
        fun `PRIORITY is stored raw, because bucketing on the way in loses it`() {
            // Priority.HIGH folds 1-4 into one bucket, so rewriting a server's
            // PRIORITY:3 as 1 would be a silent edit on the next write-back.
            assertThat(VTodoMapper.read(vtodo("PRIORITY:3")).entity.priority).isEqualTo(3)
        }

        @Test
        fun `an out-of-range PRIORITY falls back to none`() {
            assertThat(VTodoMapper.read(vtodo("PRIORITY:42")).entity.priority).isEqualTo(PRIORITY_NONE)
        }

        @Test
        fun `an unrecognised STATUS is not flattened to NEEDS-ACTION`() {
            val mapped = VTodoMapper.read(vtodo("STATUS:X-DEFERRED"))
            assertThat(mapped.entity.status).isEqualTo(TaskStatus.NEEDS_ACTION)
            // It survives in the residue instead of being rewritten.
            assertThat(mapped.entity.unknownProperties).contains("STATUS:X-DEFERRED")
        }

        @Test
        fun `RELATED-TO points up - the referencing component is the subordinate one`() {
            val mapped = VTodoMapper.read(vtodo("RELATED-TO;RELTYPE=PARENT:parent-uid"))
            assertThat(mapped.parentUid).isEqualTo("parent-uid")
        }

        @Test
        fun `RELTYPE defaults to PARENT when absent`() {
            assertThat(VTodoMapper.read(vtodo("RELATED-TO:parent-uid")).parentUid).isEqualTo("parent-uid")
        }

        @Test
        fun `a RELATED-TO with another RELTYPE is somebody else's relationship`() {
            val mapped = VTodoMapper.read(vtodo("RELATED-TO;RELTYPE=SIBLING:other-uid"))
            assertThat(mapped.parentUid).isNull()
            assertThat(mapped.entity.unknownProperties).contains("RELTYPE=SIBLING")
        }

        @Test
        fun `a parent link is reported but left in the residue`() {
            // TaskEntity holds only a local parent_id, so a parent that is not in
            // this store would leave nothing to write back. Keeping the property
            // means the relationship survives a parent we have not fetched.
            val mapped = VTodoMapper.read(
                vtodo("RELATED-TO:parent-uid", "RELATED-TO;RELTYPE=SIBLING:other-uid"),
            )
            assertThat(mapped.parentUid).isEqualTo("parent-uid")
            assertThat(mapped.entity.unknownProperties).contains("parent-uid")
            assertThat(mapped.entity.unknownProperties).contains("other-uid")
        }

        @Test
        fun `a malformed scalar stays in the residue instead of being clamped`() {
            val mapped = VTodoMapper.read(
                vtodo("PRIORITY:11", "PERCENT-COMPLETE:150", "SEQUENCE:x"),
            )
            assertThat(mapped.entity.priority).isEqualTo(PRIORITY_NONE)
            assertThat(mapped.entity.percentComplete).isNull()
            assertThat(mapped.entity.sequence).isEqualTo(0)
            val residue = mapped.entity.unknownProperties.orEmpty()
            assertThat(residue).contains("PRIORITY:11")
            assertThat(residue).contains("PERCENT-COMPLETE:150")
            assertThat(residue).contains("SEQUENCE:x")
        }

        @Test
        fun `a mixed value-type pair keeps the odd one out in the residue`() {
            // One is_all_day flag cannot author a DATE start and a timed due, and
            // flattening both to dates destroys the 09:00.
            val mapped = VTodoMapper.read(
                vtodo("DTSTART;VALUE=DATE:20260901", "DUE;TZID=Europe/Berlin:20260901T090000"),
            )
            assertThat(mapped.entity.isAllDay).isFalse()
            assertThat(mapped.entity.unknownProperties).contains("DTSTART;VALUE=DATE:20260901")
        }

        @Test
        fun `a second timezone stays in the residue rather than being collapsed`() {
            val mapped = VTodoMapper.read(
                vtodo(
                    "DTSTART;TZID=Europe/Berlin:20260901T080000",
                    "DUE;TZID=America/New_York:20260901T140000",
                ),
            )
            assertThat(mapped.entity.timezone).isEqualTo("Europe/Berlin")
            assertThat(mapped.entity.unknownProperties).contains("America/New_York")
        }

        @Test
        fun `DTSTAMP is dropped, LAST-MODIFIED is kept`() {
            val mapped = VTodoMapper.read(
                vtodo("DTSTAMP:20260901T120000Z", "LAST-MODIFIED:20260801T090000Z"),
            )
            assertThat(mapped.entity.unknownProperties.orEmpty()).doesNotContain("DTSTAMP")
            assertThat(mapped.entity.lastModified).isNotNull()
        }

        @Test
        fun `a missing UID is reported rather than invented`() {
            val bare = ICalParser.parse("BEGIN:VTODO\r\nSUMMARY:no uid\r\nEND:VTODO")
            assertThat(VTodoMapper.read(bare).uidWasMissing).isTrue()
        }

        @Test
        fun `a DUE date with no DTSTART still reads as all-day`() {
            val mapped = VTodoMapper.read(vtodo("DUE;VALUE=DATE:20260901"))
            assertThat(mapped.entity.isAllDay).isTrue()
            assertThat(mapped.entity.due).isNotNull()
        }
        @Test
        fun `every EXDATE line reaches the column the expander reads`() {
            // ⚠️ Cardinality-many, and Apple Calendar writes one line per
            // excluded occurrence. Reading only the first left the rest out of
            // expansion, so a deleted occurrence came back in the list and in
            // its reminders — while round-tripping to the server perfectly out
            // of the residue, which is what made it invisible.
            val mapped = VTodoMapper.read(
                vtodo(
                    "DTSTART:20260101T090000Z",
                    "RRULE:FREQ=DAILY",
                    "EXDATE:20260102T090000Z",
                    "EXDATE:20260103T090000Z",
                ),
            )
            assertThat(mapped.entity.exdate)
                .isEqualTo("20260102T090000Z,20260103T090000Z")
        }

        @Test
        fun `a parameterised EXDATE keeps its parameters by staying in the residue`() {
            // Claiming it would drop the TZID for good: the property never
            // reaches the residue and write re-emits it naked, so the exclusion
            // stops matching the instance it excluded.
            val mapped = VTodoMapper.read(
                vtodo(
                    "DTSTART;TZID=Europe/Berlin:20260101T090000",
                    "RRULE:FREQ=DAILY",
                    "EXDATE;TZID=Europe/Berlin:20260102T090000",
                ),
            )
            assertThat(mapped.entity.exdate).isEqualTo("20260102T090000")
            assertThat(mapped.entity.unknownProperties)
                .contains("EXDATE;TZID=Europe/Berlin:20260102T090000")
        }
    }

    @Nested
    inner class Writing {

        @Test
        fun `an unchanged parameterised EXDATE is written once, from the residue`() {
            val mapped = VTodoMapper.read(
                vtodo(
                    "DTSTART;TZID=Europe/Berlin:20260101T090000",
                    "RRULE:FREQ=DAILY",
                    "EXDATE;TZID=Europe/Berlin:20260102T090000",
                ),
            )

            val written = VTodoMapper.write(mapped.entity)

            val exdates = written.properties("EXDATE")
            assertThat(exdates).hasSize(1)
            assertThat(exdates.single().param("TZID")).isEqualTo("Europe/Berlin")
        }

        @Test
        fun `a changed EXDATE is authored with the parameters the residue gives up`() {
            val mapped = VTodoMapper.read(
                vtodo(
                    "DTSTART;TZID=Europe/Berlin:20260101T090000",
                    "RRULE:FREQ=DAILY",
                    "EXDATE;TZID=Europe/Berlin:20260102T090000",
                ),
            )
            // What deleting a second occurrence leaves behind.
            val excepted = mapped.entity.copy(
                exdate = "${mapped.entity.exdate},20260103T090000",
            )

            val written = VTodoMapper.write(excepted)

            val exdate = written.properties("EXDATE").single()
            assertThat(exdate.value).isEqualTo("20260102T090000,20260103T090000")
            assertThat(exdate.param("TZID")).isEqualTo("Europe/Berlin")
        }

        @Test
        fun `a date-shaped EXDATE we author carries VALUE=DATE`() {
            // §3.3.5's default is DATE-TIME, so a bare eight-digit value is
            // malformed — a permanent 415 under Prefer: handling=strict.
            val entity = entity().copy(isAllDay = true, exdate = "20260102")
            val exdate = VTodoMapper.write(entity).properties("EXDATE").single()
            assertThat(exdate.param("VALUE")).isEqualTo("DATE")
        }

        @Test
        fun `SEQUENCE is preserved verbatim and never bumped`() {
            // It is the Organizer's revision counter (RFC 5545 section 3.8.7.4);
            // a client that increments it confuses scheduling-aware peers.
            val entity = entity(sequence = 7)
            assertThat(VTodoMapper.write(entity).property("SEQUENCE")?.value).isEqualTo("7")
        }

        @Test
        fun `COMPLETED is written in UTC, with no TZID and no DATE form`() {
            val entity = entity(
                completedAt = Instant.parse("2026-09-01T19:00:00Z"),
                isAllDay = true,
                timezone = "Europe/Berlin",
            )
            val completed = VTodoMapper.write(entity).property("COMPLETED")
            assertThat(completed?.value).isEqualTo("20260901T190000Z")
            assertThat(completed?.params).isEmpty()
        }

        @Test
        fun `an all-day task writes VALUE=DATE`() {
            val entity = entity(due = Instant.parse("2026-09-01T00:00:00Z"), isAllDay = true)
            val due = VTodoMapper.write(entity).property("DUE")
            assertThat(due?.param("VALUE")).isEqualTo("DATE")
            assertThat(due?.value).isEqualTo("20260901")
        }

        @Test
        fun `an unedited residue property suppresses the column it came from`() {
            val entity = entity(
                // What read() would have stored: the best-effort reading of the
                // unknown-zone value it could not reproduce.
                due = Instant.parse("2026-09-10T17:00:00Z"),
                unknownProperties = "DUE;TZID=Custom/HQ:20260910T170000\r\n",
            )
            val written = VTodoMapper.write(entity)
            assertThat(written.properties("DUE")).hasSize(1)
            assertThat(written.property("DUE")?.param("TZID")).isEqualTo("Custom/HQ")
        }

        @Test
        fun `an edited column evicts the stale residue copy`() {
            // Without eviction, changing the due date of a task imported with an
            // unreproducible stamp would silently do nothing on the server.
            val entity = entity(
                due = Instant.parse("2026-09-20T17:00:00Z"),
                unknownProperties = "DUE;TZID=Custom/HQ:20260910T170000\r\n",
            )
            val written = VTodoMapper.write(entity)
            assertThat(written.properties("DUE")).hasSize(1)
            assertThat(written.property("DUE")?.value).isEqualTo("20260920T170000Z")
        }

        @Test
        fun `an unreadable STATUS in the residue is not doubled by the default`() {
            // STATUS has cardinality one; authoring NEEDS-ACTION next to the
            // residue copy produces a resource with two of them.
            val entity = entity(unknownProperties = "STATUS:X-DEFERRED\r\n")
            val written = VTodoMapper.write(entity)
            assertThat(written.properties("STATUS")).hasSize(1)
            assertThat(written.property("STATUS")?.value).isEqualTo("X-DEFERRED")
        }

        @Test
        fun `a locally set status evicts the unreadable one`() {
            val entity = entity(unknownProperties = "STATUS:X-DEFERRED\r\n")
                .copy(status = TaskStatus.COMPLETED)
            val written = VTodoMapper.write(entity)
            assertThat(written.properties("STATUS")).hasSize(1)
            assertThat(written.property("STATUS")?.value).isEqualTo("COMPLETED")
        }

        @Test
        fun `a timezone the device cannot resolve never becomes TZID on a UTC value`() {
            // RFC 5545 section 3.3.5 forbids TZID on a `...Z` value, and sabre
            // answers 415 for it.
            val entity = entity(due = Instant.parse("2026-09-05T05:30:00Z"), timezone = "Custom/Nope")
            val due = VTodoMapper.write(entity).property("DUE")
            assertThat(due?.params).isEmpty()
            assertThat(due?.value).isEqualTo("20260905T053000Z")
        }

        @Test
        fun `PRIORITY 0 is omitted, because 0 means undefined`() {
            assertThat(VTodoMapper.write(entity()).property("PRIORITY")).isNull()
        }
    }

    @Nested
    inner class Categories {

        @Test
        fun `a single plain line is claimed into the column`() {
            val mapped = VTodoMapper.read(vtodo("CATEGORIES:Work,Home\\, Garden"))
            assertThat(mapped.entity.categories).containsExactly("Work", "Home, Garden").inOrder()
            assertThat(mapped.entity.unknownProperties.orEmpty()).doesNotContain("CATEGORIES")
        }

        @Test
        fun `several lines are read but round-trip from the residue`() {
            val mapped = VTodoMapper.read(vtodo("CATEGORIES:Work", "CATEGORIES:Errands"))
            assertThat(mapped.entity.categories).containsExactly("Work", "Errands").inOrder()

            val written = VTodoMapper.write(mapped.entity).properties("CATEGORIES").map { it.value }
            assertThat(written).containsExactly("Work", "Errands")
        }

        @Test
        fun `a line the column would not reproduce exactly stays in the residue`() {
            val mapped = VTodoMapper.read(vtodo("CATEGORIES;LANGUAGE=de:Arbeit", "SUMMARY:x"))
            assertThat(mapped.entity.categories).containsExactly("Arbeit")
            assertThat(VTodoMapper.read(vtodo("CATEGORIES:a, b")).entity.unknownProperties)
                .contains("CATEGORIES:a, b")

            val written = VTodoMapper.write(mapped.entity).properties("CATEGORIES")
            assertThat(written.single().param("LANGUAGE")).isEqualTo("de")
        }

        @Test
        fun `editing the tags evicts the residue copies and authors one line`() {
            val mapped = VTodoMapper.read(vtodo("CATEGORIES:Work", "CATEGORIES:Errands"))
            val edited = mapped.entity.copy(categories = listOf("Work", "Home"))

            val written = VTodoMapper.write(edited).properties("CATEGORIES")
            assertThat(written.single().value).isEqualTo("Work,Home")
            assertThat(written.single().params).isEmpty()
        }

        @Test
        fun `no tags writes no line`() {
            assertThat(VTodoMapper.write(entity()).properties("CATEGORIES")).isEmpty()
        }

        @Test
        fun `a duplicated or blank tag is dropped and the line stays in the residue`() {
            val mapped = VTodoMapper.read(vtodo("CATEGORIES:Work, ,Work"))
            assertThat(mapped.entity.categories).containsExactly("Work")
            assertThat(VTodoMapper.write(mapped.entity).properties("CATEGORIES").single().value).isEqualTo("Work, ,Work")
        }

        @Test
        fun `an unclean column is cleaned on the way out`() {
            val written = VTodoMapper.write(entity().copy(categories = listOf(" a ", "", "a", "b")))
            assertThat(written.properties("CATEGORIES").single().value).isEqualTo("a,b")
        }
    }

    @Nested
    inner class Validation {

        @Test
        fun `DUE before DTSTART is caught here, not as a 415 from sabre`() {
            val problems = VTodoMapper.validate(
                vtodo("DTSTART:20260910T100000Z", "DUE:20260901T100000Z"),
            )
            assertThat(problems).contains("DUE precedes DTSTART")
        }

        @Test
        fun `DTSTART and DUE must agree on value type`() {
            val problems = VTodoMapper.validate(
                vtodo("DTSTART;VALUE=DATE:20260901", "DUE:20260910T100000Z"),
            )
            assertThat(problems).contains("DTSTART and DUE disagree on value type")
        }

        @Test
        fun `an end-relative alarm with no DUE is invalid, and reachable from the UI`() {
            // Clearing the due date on a task that has an end-relative reminder is
            // an ordinary UI action that produces a permanently rejected resource.
            val withAlarm = ICalParser.parse(
                """
                BEGIN:VTODO
                UID:t-1
                SUMMARY:x
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER;RELATED=END:-PT15M
                END:VALARM
                END:VTODO
                """.trimIndent(),
            )
            assertThat(VTodoMapper.validate(withAlarm))
                .contains("TRIGGER;RELATED=END with neither DUE nor DTSTART+DURATION")
        }

        @Test
        fun `METHOD is rejected, because sabre answers 415 for it`() {
            assertThat(VTodoMapper.validate(vtodo("METHOD:REQUEST")))
                .contains("METHOD is not allowed on a stored resource")
        }

        @Test
        fun `an implicit DATE is recognised as one, so value types do not falsely disagree`() {
            // A bare 8-digit value with no T is a DATE whether or not VALUE=DATE
            // says so. Two different date tests would block a legitimate PUT.
            assertThat(
                VTodoMapper.validate(vtodo("DTSTART:20260101", "DUE;VALUE=DATE:20260102")),
            ).isEmpty()
        }

        @Test
        fun `a well-formed task has no problems`() {
            assertThat(
                VTodoMapper.validate(vtodo("DTSTART:20260901T100000Z", "DUE:20260910T100000Z")),
            ).isEmpty()
        }
    }

    private fun entity(
        sequence: Int = 0,
        completedAt: Instant? = null,
        due: Instant? = null,
        isAllDay: Boolean = false,
        timezone: String? = null,
        unknownProperties: String? = null,
    ) = TaskEntity(
        listId = 1L,
        uid = "t-1",
        title = "A task",
        sequence = sequence,
        completedAt = completedAt,
        due = due,
        isAllDay = isAllDay,
        timezone = timezone,
        unknownProperties = unknownProperties,
    )

    @Nested
    inner class Alarms {

        private val due = Instant.parse("2026-10-01T15:00:00Z")
        private val task = TaskEntity(listId = 1, uid = "t-1", title = "Pay rent", due = due)

        @Test
        fun `claimed alarms leave the residue and unclaimed ones stay`() {
            val mapped = VTodoMapper.read(
                vtodo(
                    "DUE:20261001T150000Z",
                    "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER;RELATED=END:-PT15M", "END:VALARM",
                    "BEGIN:VALARM", "ACTION:EMAIL", "TRIGGER;RELATED=END:-PT1H", "END:VALARM",
                ),
            )
            assertThat(mapped.alarms.map { it.minutesBefore }).containsExactly(15)
            assertThat(mapped.entity.unknownProperties).contains("ACTION:EMAIL")
            assertThat(mapped.entity.unknownProperties).doesNotContain("ACTION:DISPLAY")
        }

        @Test
        fun `the task's reminders are written ahead of the residue's alarms`() {
            val withResidue = task.copy(
                unknownProperties = "BEGIN:VALARM\r\nACTION:EMAIL\r\nTRIGGER;RELATED=END:-PT1H\r\nEND:VALARM\r\n",
            )
            val written = VTodoMapper.write(
                withResidue,
                now = due - 1.days,
                alarms = listOf(TaskAlarmEntity(taskId = 1, minutesBefore = 15, uid = "a-1")),
            )
            val alarms = written.components("VALARM")
            assertThat(alarms.map { it.property("ACTION")?.value }).containsExactly("DISPLAY", "EMAIL").inOrder()
            assertThat(alarms.first().property("TRIGGER")?.value).isEqualTo("-PT15M")
        }

        @Test
        fun `a reminder left behind by a cleared due date does not make the task unuploadable`() {
            val cleared = task.copy(due = null)
            val written = VTodoMapper.write(
                cleared,
                alarms = listOf(TaskAlarmEntity(taskId = 1, minutesBefore = 15, uid = "a-1")),
            )
            assertThat(written.components("VALARM")).isEmpty()
            assertThat(VTodoMapper.validate(written)).isEmpty()
        }

        @Test
        fun `a reminder survives write and read with its identity`() {
            val written = VTodoMapper.write(
                task,
                now = due - 1.days,
                alarms = listOf(TaskAlarmEntity(taskId = 1, minutesBefore = 30, uid = "a-1")),
            )
            val back = VTodoMapper.read(written).alarms.single()
            assertThat(back.minutesBefore).isEqualTo(30)
            assertThat(back.reference).isEqualTo(AlarmReference.DUE)
            assertThat(back.uid).isEqualTo("a-1")
        }
    }
}

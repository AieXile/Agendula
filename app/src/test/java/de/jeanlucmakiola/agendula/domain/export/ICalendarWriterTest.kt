package de.jeanlucmakiola.agendula.domain.export

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.TaskStatus
import de.jeanlucmakiola.agendula.domain.allDayInstantOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.time.Instant

/**
 * The export format. Worth testing closely: an export is only as good as its
 * ability to be read back, and nothing about a malformed `.ics` is obvious until
 * someone actually needs the backup.
 */
class ICalendarWriterTest {

    private fun task(
        taskId: Long = 1L,
        uid: String? = null,
        title: String = "Buy oat milk",
        description: String? = null,
        location: String? = null,
        url: String? = null,
        priority: Priority = Priority.NONE,
        status: TaskStatus = TaskStatus.NEEDS_ACTION,
        percentComplete: Int? = null,
        start: Instant? = null,
        due: Instant? = null,
        isAllDay: Boolean = false,
        completedAt: Instant? = null,
        created: Instant? = null,
        lastModified: Instant? = null,
        rrule: String? = null,
        rdate: String? = null,
        parentId: Long? = null,
        tags: List<String> = emptyList(),
    ) = ExportTask(
        taskId, uid, title, description, location, url, priority, status, percentComplete,
        start, due, isAllDay, completedAt, created, lastModified, rrule, rdate, parentId, tags,
    )

    private fun write(vararg tasks: ExportTask, name: String = "Groceries"): String =
        ICalendarWriter.write(ExportList(1L, name, "local", tasks.toList()))

    /** Unfolds the way an importer does, so assertions can read logical lines. */
    private fun String.unfolded(): String = replace("\r\n ", "")

    private fun linesOf(ics: String): List<String> = ics.unfolded().split("\r\n").filter { it.isNotEmpty() }

    @Nested
    inner class Structure {

        @Test
        fun `wraps the todos in a calendar`() {
            val lines = linesOf(write(task()))
            assertThat(lines.first()).isEqualTo("BEGIN:VCALENDAR")
            assertThat(lines.last()).isEqualTo("END:VCALENDAR")
            assertThat(lines).containsAtLeast("VERSION:2.0", "BEGIN:VTODO", "END:VTODO")
        }

        @Test
        fun `writes the tags as one CATEGORIES line, escaped`() {
            val lines = linesOf(write(task(tags = listOf("Work", "Home, Garden"))))
            assertThat(lines).contains("CATEGORIES:Work,Home\\, Garden")
            assertThat(linesOf(write(task())).none { it.startsWith("CATEGORIES") }).isTrue()
        }

        @Test
        fun `uses CRLF line endings`() {
            // RFC 5545 requires CRLF. Bare LF is the classic way an .ics is rejected
            // by a strict importer while looking perfectly fine in an editor.
            val ics = write(task())
            assertThat(ics).contains("\r\n")
            assertThat(ics.replace("\r\n", "")).doesNotContain("\n")
        }

        @Test
        fun `carries the list name`() {
            assertThat(linesOf(write(task(), name = "Shopping"))).contains("X-WR-CALNAME:Shopping")
        }

        @Test
        fun `every todo has a UID and a DTSTAMP`() {
            // Both are mandatory; a VTODO missing either is invalid.
            val lines = linesOf(write(task(), task(taskId = 2)))
            assertThat(lines.count { it.startsWith("UID:") }).isEqualTo(2)
            assertThat(lines.count { it.startsWith("DTSTAMP:") }).isEqualTo(2)
        }
    }

    @Nested
    inner class Identity {

        @Test
        fun `prefers the synced UID`() {
            assertThat(linesOf(write(task(uid = "abc-123@example.org"))))
                .contains("UID:abc-123@example.org")
        }

        @Test
        fun `synthesises a stable UID for a local task`() {
            // Local tasks never get a UID from the provider (only a sync adapter may
            // assign one), and re-importing UID-less todos would duplicate rather
            // than match them.
            val first = ICalendarWriter.uidFor(task(taskId = 42))
            val second = ICalendarWriter.uidFor(task(taskId = 42))
            assertThat(first).isEqualTo(second)
            assertThat(first).contains("42")
        }

        @Test
        fun `distinct tasks get distinct UIDs`() {
            assertThat(ICalendarWriter.uidFor(task(taskId = 1)))
                .isNotEqualTo(ICalendarWriter.uidFor(task(taskId = 2)))
        }

        @Test
        fun `blank stored UID falls back to the synthesised one`() {
            assertThat(ICalendarWriter.uidFor(task(taskId = 7, uid = "  "))).contains("7")
        }
    }

    @Nested
    inner class Dates {

        private val noon = Instant.fromEpochMilliseconds(1_754_136_000_000L) // 2025-08-02T12:00:00Z

        @Test
        fun `timed values are written in UTC`() {
            assertThat(linesOf(write(task(due = noon)))).contains("DUE:20250802T120000Z")
        }

        @Test
        fun `all-day values are date-only`() {
            // A DATE-TIME here would drift by a day for anyone east or west of UTC —
            // the exact bug the AllDayTime convention exists to prevent.
            val due = allDayInstantOf(LocalDate.of(2026, 8, 2))
            val lines = linesOf(write(task(due = due, isAllDay = true)))
            assertThat(lines).contains("DUE;VALUE=DATE:20260802")
        }

        @Test
        fun `completion is always a UTC date-time even when all-day`() {
            val lines = linesOf(
                write(task(isAllDay = true, status = TaskStatus.COMPLETED, completedAt = noon)),
            )
            assertThat(lines).contains("COMPLETED:20250802T120000Z")
        }

        @Test
        fun `absent dates emit no property at all`() {
            val lines = linesOf(write(task()))
            assertThat(lines.none { it.startsWith("DUE") }).isTrue()
            assertThat(lines.none { it.startsWith("DTSTART") }).isTrue()
        }
    }

    @Nested
    inner class Fields {

        @Test
        fun `maps every status to its iCalendar name`() {
            fun statusLine(status: TaskStatus) =
                linesOf(write(task(status = status))).first { it.startsWith("STATUS:") }

            assertThat(statusLine(TaskStatus.NEEDS_ACTION)).isEqualTo("STATUS:NEEDS-ACTION")
            assertThat(statusLine(TaskStatus.IN_PROCESS)).isEqualTo("STATUS:IN-PROCESS")
            assertThat(statusLine(TaskStatus.COMPLETED)).isEqualTo("STATUS:COMPLETED")
            assertThat(statusLine(TaskStatus.CANCELLED)).isEqualTo("STATUS:CANCELLED")
        }

        @Test
        fun `omits priority when there is none`() {
            // PRIORITY:0 means "undefined" but reads as a real value to some
            // importers; leaving it out is unambiguous.
            assertThat(linesOf(write(task())).none { it.startsWith("PRIORITY") }).isTrue()
            assertThat(linesOf(write(task(priority = Priority.HIGH)))).contains("PRIORITY:1")
        }

        @Test
        fun `clamps percent complete into range`() {
            assertThat(linesOf(write(task(percentComplete = 140)))).contains("PERCENT-COMPLETE:100")
            assertThat(linesOf(write(task(percentComplete = -5)))).contains("PERCENT-COMPLETE:0")
        }

        @Test
        fun `passes recurrence through unchanged`() {
            val lines = linesOf(write(task(rrule = "FREQ=WEEKLY;BYDAY=MO,WE")))
            assertThat(lines).contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE")
        }

        @Test
        fun `does not escape a URL`() {
            // URL is a URI, not TEXT. Escaping its commas would corrupt the address.
            val lines = linesOf(write(task(url = "https://example.org/a,b;c")))
            assertThat(lines).contains("URL:https://example.org/a,b;c")
        }

        @Test
        fun `skips blank optional fields`() {
            val lines = linesOf(write(task(description = "   ", location = "", url = "")))
            assertThat(lines.none { it.startsWith("DESCRIPTION") }).isTrue()
            assertThat(lines.none { it.startsWith("LOCATION") }).isTrue()
            assertThat(lines.none { it.startsWith("URL") }).isTrue()
        }
    }

    @Nested
    inner class Subtasks {

        @Test
        fun `links a child to its parent by UID`() {
            val parent = task(taskId = 1, uid = "parent@example.org")
            val child = task(taskId = 2, parentId = 1)
            assertThat(linesOf(write(parent, child)))
                .contains("RELATED-TO;RELTYPE=PARENT:parent@example.org")
        }

        @Test
        fun `resolves a parent that appears after the child`() {
            // Nothing guarantees provider order, and a forward reference must still
            // resolve or half the hierarchy silently disappears.
            val child = task(taskId = 2, parentId = 1)
            val parent = task(taskId = 1, uid = "parent@example.org")
            assertThat(linesOf(write(child, parent)))
                .contains("RELATED-TO;RELTYPE=PARENT:parent@example.org")
        }

        @Test
        fun `drops a link to a parent outside this list`() {
            // A RELATED-TO pointing at a UID not in the file would dangle on import.
            val orphan = task(taskId = 2, parentId = 999)
            assertThat(linesOf(write(orphan)).none { it.startsWith("RELATED-TO") }).isTrue()
        }
    }

    @Nested
    inner class Escaping {

        @Test
        fun `escapes the special characters`() {
            assertThat(ICalendarWriter.escapeText("a;b,c")).isEqualTo("a\\;b\\,c")
            assertThat(ICalendarWriter.escapeText("line\nbreak")).isEqualTo("line\\nbreak")
            assertThat(ICalendarWriter.escapeText("CRLF\r\nhere")).isEqualTo("CRLF\\nhere")
        }

        @Test
        fun `escapes backslashes first`() {
            // Doing it later would re-escape the backslashes the other rules add,
            // turning "a;b" into "a\\;b".
            assertThat(ICalendarWriter.escapeText("back\\slash")).isEqualTo("back\\\\slash")
            assertThat(ICalendarWriter.escapeText("a\\;b")).isEqualTo("a\\\\\\;b")
        }

        @Test
        fun `a multiline description stays one logical line`() {
            val ics = write(task(description = "first\nsecond"))
            assertThat(ics.unfolded()).contains("DESCRIPTION:first\\nsecond")
        }
    }

    @Nested
    inner class Folding {

        @Test
        fun `short lines are untouched`() {
            assertThat(ICalendarWriter.fold("SUMMARY:short")).isEqualTo("SUMMARY:short")
        }

        @Test
        fun `long lines are folded to 75 octets`() {
            val folded = ICalendarWriter.fold("SUMMARY:" + "a".repeat(200))
            folded.split("\r\n").forEachIndexed { index, segment ->
                val octets = segment.toByteArray(Charsets.UTF_8).size
                assertThat(octets).isAtMost(if (index == 0) 75 else 76) // 75 + the leading space
            }
        }

        @Test
        fun `folding round-trips`() {
            val original = "DESCRIPTION:" + "long text ".repeat(40)
            assertThat(ICalendarWriter.fold(original).replace("\r\n ", "")).isEqualTo(original)
        }

        @Test
        fun `never splits a multi-byte character`() {
            // The limit is in octets but an emoji is four of them; splitting mid
            // sequence would emit invalid UTF-8 and mangle the title.
            val emoji = "SUMMARY:" + "🌼".repeat(40)
            val folded = ICalendarWriter.fold(emoji)
            assertThat(folded.replace("\r\n ", "")).isEqualTo(emoji)
            folded.split("\r\n").forEach { segment ->
                // A broken surrogate pair round-trips through UTF-8 as U+FFFD.
                assertThat(segment.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
                    .isEqualTo(segment)
            }
        }

        @Test
        fun `a long title survives the full write`() {
            val title = "Remember to ".repeat(20)
            assertThat(write(task(title = title)).unfolded()).contains("SUMMARY:$title")
        }
    }

    @Nested
    inner class EmptyList {

        @Test
        fun `still produces a valid calendar`() {
            // An empty list is a real answer; a missing file is indistinguishable
            // from a failed export.
            val lines = linesOf(ICalendarWriter.write(ExportList(1L, "Empty", "local", emptyList())))
            assertThat(lines.first()).isEqualTo("BEGIN:VCALENDAR")
            assertThat(lines.last()).isEqualTo("END:VCALENDAR")
            assertThat(lines.none { it == "BEGIN:VTODO" }).isTrue()
        }
    }
}

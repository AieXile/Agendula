package de.jeanlucmakiola.agendula.domain.ical

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The lexer. Worth testing closely because every correctness guarantee above it
 * assumes a content line was split where RFC 5545 says it splits — and the
 * places it does not split are where other clients' data goes missing.
 */
class ICalParserTest {

    @Nested
    inner class Unfolding {

        @Test
        fun `unfolding removes the CRLF and the whitespace, not just the CRLF`() {
            // RFC 5545 section 3.1: a fold is CRLF + one WSP and *both* go. A
            // folder that keeps the space silently inserts one into every long
            // value it ever wrote.
            val lines = ICalParser.unfold("SUMMARY:Hello\r\n world\r\n")
            assertThat(lines).containsExactly("SUMMARY:Helloworld")
        }

        @Test
        fun `a tab folds too`() {
            assertThat(ICalParser.unfold("SUMMARY:Hello\r\n\tworld")).containsExactly("SUMMARY:Helloworld")
        }

        @Test
        fun `bare LF is accepted, because real files carry it`() {
            assertThat(ICalParser.unfold("A:1\nB:2")).containsExactly("A:1", "B:2")
        }

        @Test
        fun `a leading BOM is stripped rather than glued onto the first name`() {
            val component = ICalParser.parse("\uFEFFBEGIN:VTODO\r\nUID:x\r\nEND:VTODO\r\n")
            assertThat(component.name).isEqualTo("VTODO")
        }
    }

    @Nested
    inner class ContentLines {

        @Test
        fun `the value starts at the first colon outside a quoted parameter`() {
            val property = ICalParser.parseLine("""ATTENDEE;CN="Smith, J:r":mailto:jr@example.com""")
            assertThat(property?.name).isEqualTo("ATTENDEE")
            assertThat(property?.param("CN")).isEqualTo("Smith, J:r")
            assertThat(property?.value).isEqualTo("mailto:jr@example.com")
        }

        @Test
        fun `a multi-valued parameter splits only on unquoted commas`() {
            val property = ICalParser.parseLine("""X-THING;MEMBER="a,b",c:v""")
            assertThat(property?.params?.single()?.values).containsExactly("a,b", "c").inOrder()
        }

        @Test
        fun `a value may contain colons`() {
            assertThat(ICalParser.parseLine("URL:https://example.com/a:b")?.value)
                .isEqualTo("https://example.com/a:b")
        }

        @Test
        fun `the value is kept escaped, because that is what makes it reproducible`() {
            assertThat(ICalParser.parseLine("""DESCRIPTION:a\, b\; c""")?.value)
                .isEqualTo("""a\, b\; c""")
        }

        @Test
        fun `a parameter with no equals sign is kept rather than dropped`() {
            val property = ICalParser.parseLine("X-THING;BROKEN:v")
            assertThat(property?.params?.single()?.name).isEqualTo("BROKEN")
            assertThat(property?.params?.single()?.values).isEmpty()
        }

        @Test
        fun `a line with no colon is not a content line`() {
            assertThat(ICalParser.parseLine("GARBAGE")).isNull()
        }
    }

    @Nested
    inner class Structure {

        @Test
        fun `unknown components nest like any other`() {
            val component = ICalParser.parse(
                """
                BEGIN:VTODO
                UID:x
                BEGIN:X-VENDOR
                X-KEY:v
                END:X-VENDOR
                END:VTODO
                """.trimIndent(),
            )
            assertThat(component.components("X-VENDOR").single().property("X-KEY")?.value).isEqualTo("v")
        }

        @Test
        fun `a stray END is ignored rather than fatal`() {
            val component = ICalParser.parse("END:VALARM\nBEGIN:VTODO\nUID:x\nEND:VTODO\n")
            assertThat(component.name).isEqualTo("VTODO")
            assertThat(component.property("UID")?.value).isEqualTo("x")
        }

        @Test
        fun `component names are upper-cased so lookups are not case-dependent`() {
            assertThat(ICalParser.parse("begin:vtodo\nuid:x\nend:vtodo\n").name).isEqualTo("VTODO")
        }

        @Test
        fun `text with no component at all is fatal`() {
            runCatching { ICalParser.parse("SUMMARY:orphan\r\n") }
                .onSuccess { error("expected ICalParseException") }
                .onFailure { assertThat(it).isInstanceOf(ICalParseException::class.java) }
        }

        @Test
        fun `a missing END does not swallow the enclosing component`() {
            // Closing by position would let END:VCALENDAR close the VTODO, leave
            // VCALENDAR open, and end with no component at all — discarding a
            // whole multiget response over one malformed task.
            val roots = ICalParser.parseAll(
                "BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:x\r\nEND:VCALENDAR\r\n",
            )
            val calendar = roots.single()
            assertThat(calendar.name).isEqualTo("VCALENDAR")
            assertThat(calendar.components("VTODO").single().property("UID")?.value).isEqualTo("x")
        }

        @Test
        fun `a component left open at end of input is still returned`() {
            val calendar = ICalParser.parse("BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:x\r\n")
            assertThat(calendar.components("VTODO").single().property("UID")?.value).isEqualTo("x")
        }

        @Test
        fun `an unbalanced quote does not swallow the whole line`() {
            val property = ICalParser.parseLine("""X-PROP;CN="unclosed:value""")
            assertThat(property?.name).isEqualTo("X-PROP")
            assertThat(property?.value).isEqualTo("value")
        }

        @Test
        fun `duplicate properties are both kept`() {
            val component = ICalParser.parse("BEGIN:VTODO\nCATEGORIES:a\nCATEGORIES:a\nEND:VTODO\n")
            assertThat(component.properties("CATEGORIES")).hasSize(2)
        }
    }
}

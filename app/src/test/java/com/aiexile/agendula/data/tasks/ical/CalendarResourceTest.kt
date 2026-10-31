package com.aiexile.agendula.data.tasks.ical

import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.domain.ical.ICalParam
import com.aiexile.agendula.domain.ical.ICalComponent
import com.aiexile.agendula.domain.ical.ICalProperty
import org.junit.jupiter.api.Test

class CalendarResourceTest {

    @Test fun `a zoned task carries the VTIMEZONE it references`() {
        val todo = todo(ICalParam("TZID", "Europe/Berlin"))

        val text = CalendarResource.serialize(listOf(todo))

        // ⚠️ RFC 5545 §3.2.19: a TZID without a leading solidus must reference a
        // VTIMEZONE in the same object. Omitting it makes the resource malformed,
        // and `Prefer: handling=strict` turns malformed into rejected.
        assertThat(text).contains("BEGIN:VTIMEZONE")
        assertThat(text).contains("TZID:Europe/Berlin")
        // Definition before reference, for a server that parses as it streams.
        assertThat(text.indexOf("BEGIN:VTIMEZONE")).isLessThan(text.indexOf("BEGIN:VTODO"))
    }

    @Test fun `a UTC task carries no timezone at all`() {
        val text = CalendarResource.serialize(listOf(todo()))
        assertThat(text).doesNotContain("VTIMEZONE")
    }

    @Test fun `one definition serves every task that shares a zone`() {
        val text = CalendarResource.serialize(
            listOf(todo(ICalParam("TZID", "Europe/Berlin")), todo(ICalParam("TZID", "Europe/Berlin"))),
        )
        assertThat(text.split("BEGIN:VTIMEZONE")).hasSize(2)
    }

    @Test fun `the wrapper is a parseable VCALENDAR`() {
        val text = CalendarResource.serialize(listOf(todo()))
        val parsed = CalendarResource.parse(text)

        assertThat(parsed).hasSize(1)
        assertThat(parsed.single().property("VERSION")!!.value).isEqualTo("2.0")
        assertThat(parsed.single().property("PRODID")!!.value)
            .isEqualTo(CalendarResource.PRODUCT_ID)
        assertThat(CalendarResource.todosIn(parsed)).hasSize(1)
    }

    @Test fun `a body that is not a calendar yields no calendars`() {
        assertThat(CalendarResource.parse("BEGIN:VCARD\r\nEND:VCARD\r\n")).isEmpty()
    }

    @Test fun `an unparseable scalar is never emitted twice`() {
        // ⚠️ Every one of these reaches the residue verbatim because it could not
        // be parsed, and `write` authors its column form unconditionally. Without
        // suppression the resource carries both — and `Prefer: handling=strict`
        // means sabre will not quietly repair the duplicate.
        val source = javaClass.classLoader!!
            .getResourceAsStream("vtodo/malformed-scalars.ics")!!
            .readBytes().decodeToString()
        val vtodo = CalendarResource.todosIn(CalendarResource.parse(source)).single()

        val written = CalendarResource.serialize(
            listOf(VTodoMapper.write(VTodoMapper.read(vtodo).entity)),
        )

        listOf("SEQUENCE", "PRIORITY", "PERCENT-COMPLETE", "CLASS", "STATUS").forEach { name ->
            assertThat(Regex("(?m)^$name[;:]").findAll(written).count())
                .isEqualTo(1)
        }
        // The residue's copy is the one that survives, verbatim.
        assertThat(written).contains("SEQUENCE:x")
        assertThat(written).contains("PRIORITY:11")
    }

    private fun todo(vararg params: ICalParam) = ICalComponent(
        name = "VTODO",
        properties = listOf(
            ICalProperty("UID", emptyList(), "a"),
            ICalProperty("DTSTART", params.toList(), "20260301T090000"),
        ),
        components = emptyList(),
    )
}

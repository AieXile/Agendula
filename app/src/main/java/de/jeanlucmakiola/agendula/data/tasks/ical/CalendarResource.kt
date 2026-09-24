package de.jeanlucmakiola.agendula.data.tasks.ical

import de.jeanlucmakiola.agendula.domain.ical.ICalComponent
import de.jeanlucmakiola.agendula.domain.ical.ICalParser
import de.jeanlucmakiola.agendula.domain.ical.ICalProperty
import de.jeanlucmakiola.agendula.domain.ical.ICalSerializer
import de.jeanlucmakiola.agendula.domain.ical.VTimeZones

/**
 * One CalDAV resource: the `VCALENDAR` wrapper around the `VTODO`s that share a
 * `UID`.
 *
 * ⚠️ A resource is **not** a task. RFC 4791 §4.1 requires every component in one
 * resource to share a UID, which makes a recurring task and all of its
 * `RECURRENCE-ID` overrides exactly one resource — and makes two unrelated tasks
 * in one resource unuploadable. The engine works in resources; the mapper works
 * in components.
 */
object CalendarResource {

    const val PRODUCT_ID = "-//Jean-Luc Makiola//Agendula//EN"
    const val VERSION = "2.0"

    /** Wraps [vtodos] with the `VTIMEZONE`s their `TZID` parameters reference. */
    fun build(vtodos: List<ICalComponent>): ICalComponent {
        val zones = vtodos
            .flatMap { VTimeZones.forComponent(it) }
            .distinctBy { it.property("TZID")?.value }
        return ICalComponent(
            name = "VCALENDAR",
            properties = listOf(
                ICalProperty("VERSION", emptyList(), VERSION),
                ICalProperty("PRODID", emptyList(), PRODUCT_ID),
            ),
            // Zones first: a server that streams the object as it parses has the
            // definition before the reference.
            components = zones + vtodos,
        )
    }

    fun serialize(vtodos: List<ICalComponent>): String =
        ICalSerializer.serialize(build(vtodos))

    /**
     * The `VCALENDAR`s in a downloaded body.
     *
     * More than one is malformed but does happen; the caller decides what to do
     * about it rather than having the decision made here by a parser.
     */
    fun parse(text: String): List<ICalComponent> =
        ICalParser.parseAll(text).filter { it.name.equals("VCALENDAR", ignoreCase = true) }

    /** Every `VTODO` across [calendars], in document order. */
    fun todosIn(calendars: List<ICalComponent>): List<ICalComponent> =
        calendars.flatMap { it.components("VTODO") }
}

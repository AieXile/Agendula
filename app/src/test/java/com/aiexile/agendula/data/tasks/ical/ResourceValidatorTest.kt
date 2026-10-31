package com.aiexile.agendula.data.tasks.ical

import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.domain.ical.ICalParser
import org.junit.jupiter.api.Test

/**
 * Every case here is a real sabre rejection reachable from the UI, not a
 * defensive check. A 415 is permanent: the row stays dirty and the next sync
 * sends the same bytes forever.
 */
class ResourceValidatorTest {

    @Test fun `an ordinary task passes`() {
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            UID:a
            SUMMARY:Buy milk
            END:VTODO
        """))).isNull()
    }

    @Test fun `DUE before DTSTART is refused`() {
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            UID:a
            DTSTART:20260301T100000Z
            DUE:20260301T090000Z
            END:VTODO
        """))).contains("DUE precedes DTSTART")
    }

    @Test fun `equal DUE and DTSTART is allowed`() {
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            UID:a
            DTSTART:20260301T100000Z
            DUE:20260301T100000Z
            END:VTODO
        """))).isNull()
    }

    @Test fun `a value-type mismatch is refused`() {
        // Reachable: set an all-day due date on a task that already has a timed
        // start.
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            UID:a
            DTSTART:20260301T100000Z
            DUE;VALUE=DATE:20260302
            END:VTODO
        """))).contains("disagree on value type")
    }

    @Test fun `two DATE values are not a mismatch`() {
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            UID:a
            DTSTART;VALUE=DATE:20260301
            DUE;VALUE=DATE:20260302
            END:VTODO
        """))).isNull()
    }

    @Test fun `METHOD makes it a scheduling message`() {
        assertThat(reject("""
            BEGIN:VCALENDAR
            VERSION:2.0
            METHOD:REQUEST
            BEGIN:VTODO
            UID:a
            END:VTODO
            END:VCALENDAR
        """)).contains("METHOD")
    }

    @Test fun `DUE with DURATION is refused`() {
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            UID:a
            DTSTART:20260301T100000Z
            DUE:20260301T110000Z
            DURATION:PT1H
            END:VTODO
        """))).contains("DUE and DURATION")
    }

    @Test fun `two UIDs in one resource are refused`() {
        // RFC 4791 §4.1 — this is the constraint that makes forking a conflicting
        // edit into the same resource impossible.
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            UID:a
            END:VTODO
            BEGIN:VTODO
            UID:b
            END:VTODO
        """))).contains("different UIDs")
    }

    @Test fun `overrides sharing a UID are fine`() {
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            UID:a
            RRULE:FREQ=DAILY
            END:VTODO
            BEGIN:VTODO
            UID:a
            RECURRENCE-ID:20260302T090000Z
            END:VTODO
        """))).isNull()
    }

    @Test fun `a mixed component type is refused`() {
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            UID:a
            END:VTODO
            BEGIN:VEVENT
            UID:a
            END:VEVENT
        """))).contains("VEVENT")
    }

    @Test fun `a VTIMEZONE alongside the task is not foreign`() {
        assertThat(reject(vcalendar("""
            BEGIN:VTIMEZONE
            TZID:Europe/Berlin
            END:VTIMEZONE
            BEGIN:VTODO
            UID:a
            END:VTODO
        """))).isNull()
    }

    @Test fun `a missing UID is refused`() {
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            SUMMARY:no identity
            END:VTODO
        """))).contains("no UID")
    }

    @Test fun `an implicit DATE does not falsely disagree`() {
        // Two value-type tests that disagree block a legitimate PUT: the mapper
        // reads a bare eight-digit value as a DATE, and so must this.
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            UID:a
            DTSTART:20260301
            DUE;VALUE=DATE:20260302
            END:VTODO
        """))).isNull()
    }

    @Test fun `an end-relative reminder with nothing to anchor it is refused`() {
        // Reachable from the UI: clear the due date on a task that has an
        // end-relative reminder.
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            UID:a
            BEGIN:VALARM
            TRIGGER;RELATED=END:-PT15M
            END:VALARM
            END:VTODO
        """))).contains("RELATED=END")
    }

    @Test fun `a TZID with no definition is refused`() {
        // A Windows zone name from another client survives in the residue, and
        // `java.time` cannot regenerate a VTIMEZONE for it.
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            UID:a
            DTSTART;TZID=W. Europe Standard Time:20260301T090000
            END:VTODO
        """))).contains("unknown time zone")
    }

    @Test fun `a solidus-prefixed TZID needs no local definition`() {
        // Thunderbird writes this on every zoned task. §3.2.19 exempts the
        // solidus form, and rejecting it would quarantine those tasks forever.
        assertThat(reject(vcalendar("""
            BEGIN:VTODO
            UID:a
            DTSTART;TZID=/mozilla.org/20050126_1/Europe/Berlin:20260301T090000
            END:VTODO
        """))).isNull()
    }

    @Test fun `a TZID with its definition present is fine`() {
        assertThat(reject(vcalendar("""
            BEGIN:VTIMEZONE
            TZID:Europe/Berlin
            END:VTIMEZONE
            BEGIN:VTODO
            UID:a
            DTSTART;TZID=Europe/Berlin:20260301T090000
            END:VTODO
        """))).isNull()
    }

    @Test fun `a calendar with no task is refused`() {
        assertThat(reject(vcalendar(""))).contains("no VTODO")
    }

    private fun reject(text: String): String? =
        ResourceValidator.validate(ICalParser.parse(crlf(text)))?.reason

    private fun vcalendar(body: String) =
        "BEGIN:VCALENDAR\nVERSION:2.0\n$body\nEND:VCALENDAR"

    /** Indentation-insensitive, so the fixtures can be written inline. */
    private fun crlf(text: String) = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\r\n") + "\r\n"
}

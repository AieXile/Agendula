package de.jeanlucmakiola.agendula.domain.ical

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class VTimeZonesTest {

    @Test fun `a DST zone gets both observances`() {
        val zone = VTimeZones.forZone("Europe/Berlin")!!

        assertThat(zone.property("TZID")!!.value).isEqualTo("Europe/Berlin")
        assertThat(zone.components.map { it.name })
            .containsExactly("STANDARD", "DAYLIGHT")

        val daylight = zone.components("DAYLIGHT").single()
        assertThat(daylight.property("TZOFFSETFROM")!!.value).isEqualTo("+0100")
        assertThat(daylight.property("TZOFFSETTO")!!.value).isEqualTo("+0200")
        // The EU rule is "the last Sunday", which is what java.time encodes as
        // dayOfMonthIndicator = -1.
        assertThat(daylight.property("RRULE")!!.value)
            .isEqualTo("FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU")
    }

    @Test fun `a nth-weekday rule becomes an ordinal BYDAY`() {
        val zone = VTimeZones.forZone("America/New_York")!!
        // US DST starts on the second Sunday in March, which java.time encodes as
        // "on or after the 8th".
        assertThat(zone.components("DAYLIGHT").single().property("RRULE")!!.value)
            .isEqualTo("FREQ=YEARLY;BYMONTH=3;BYDAY=2SU")
    }

    @Test fun `a fixed-offset zone gets one standard observance and no rule`() {
        val zone = VTimeZones.forZone("Asia/Kolkata")!!
        val standard = zone.components.single()

        assertThat(standard.name).isEqualTo("STANDARD")
        assertThat(standard.property("TZOFFSETTO")!!.value).isEqualTo("+0530")
        assertThat(standard.property("RRULE")).isNull()
    }

    @Test fun `UTC is representable`() {
        val zone = VTimeZones.forZone("UTC")!!
        assertThat(zone.components.single().property("TZOFFSETTO")!!.value).isEqualTo("+0000")
    }

    @Test fun `a negative offset keeps its sign`() {
        val zone = VTimeZones.forZone("America/New_York")!!
        assertThat(zone.components("STANDARD").single().property("TZOFFSETTO")!!.value)
            .isEqualTo("-0500")
    }

    @Test fun `an unknown zone is null rather than an exception`() {
        assertThat(VTimeZones.forZone("Mars/Olympus_Mons")).isNull()
        assertThat(VTimeZones.forZone("")).isNull()
    }

    @Test fun `observance DTSTART is a real instance of its own rule`() {
        val daylight = VTimeZones.forZone("Europe/Berlin")!!.components("DAYLIGHT").single()
        val start = daylight.property("DTSTART")!!.value
        // 1970-03-29 was a Sunday, and the last one in March.
        assertThat(start).isEqualTo("19700329T020000")
    }

    @Test fun `zones are collected from every TZID in the tree`() {
        val todo = ICalComponent(
            name = "VTODO",
            properties = listOf(
                ICalProperty("DTSTART", listOf(ICalParam("TZID", "Europe/Berlin")), "20260101T090000"),
                ICalProperty("DUE", listOf(ICalParam("TZID", "Europe/Berlin")), "20260101T100000"),
            ),
            components = listOf(
                ICalComponent(
                    name = "VALARM",
                    properties = listOf(
                        ICalProperty(
                            "TRIGGER",
                            listOf(ICalParam("TZID", "America/New_York")),
                            "20260101T080000",
                        ),
                    ),
                    components = emptyList(),
                ),
            ),
        )

        val zones = VTimeZones.forComponent(todo).map { it.property("TZID")!!.value }
        // Deduplicated, and the nested one is not missed.
        assertThat(zones).containsExactly("America/New_York", "Europe/Berlin").inOrder()
    }

    @Test fun `a component with no TZID needs no definitions`() {
        val todo = ICalComponent(
            name = "VTODO",
            properties = listOf(ICalProperty("DTSTART", emptyList(), "20260101T090000Z")),
            components = emptyList(),
        )
        assertThat(VTimeZones.forComponent(todo)).isEmpty()
    }
}

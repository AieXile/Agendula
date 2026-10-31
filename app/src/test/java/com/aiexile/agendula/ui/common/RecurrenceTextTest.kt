package com.aiexile.agendula.ui.common

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Test

/**
 * The read side of the UNTIL round-trip. `SimpleRecurrence.toRRule` deliberately
 * writes the end of the chosen *local* day expressed in UTC, so displaying it
 * means converting back into the device zone first.
 */
class RecurrenceTextTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val losAngeles = ZoneId.of("America/Los_Angeles")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `UTC form converts back to the picked day west of UTC`() {
        // toRRule("2026-12-31", America/Los_Angeles) → 23:59:59 local = 07:59:59Z
        // on 1 Jan. Reading the digits raw would show 1 Jan 2027.
        assertThat(untilLocalDate("20270101T075959Z", losAngeles))
            .isEqualTo(LocalDate.of(2026, 12, 31))
    }

    @Test
    fun `UTC form converts back to the picked day east of UTC`() {
        // Berlin in December is UTC+1: 23:59:59 local = 22:59:59Z the same day.
        assertThat(untilLocalDate("20261231T225959Z", berlin))
            .isEqualTo(LocalDate.of(2026, 12, 31))
    }

    @Test
    fun `UTC form is unchanged at UTC itself`() {
        assertThat(untilLocalDate("20261231T235959Z", utc))
            .isEqualTo(LocalDate.of(2026, 12, 31))
    }

    @Test
    fun `summer offset is honoured, not a fixed one`() {
        // Berlin in July is UTC+2, so the same local end-of-day lands at 21:59:59Z.
        assertThat(untilLocalDate("20260801T215959Z", berlin))
            .isEqualTo(LocalDate.of(2026, 8, 1))
    }

    @Test
    fun `date-only form is already local and passes through`() {
        assertThat(untilLocalDate("20261231", losAngeles))
            .isEqualTo(LocalDate.of(2026, 12, 31))
    }

    @Test
    fun `floating date-time form uses its date as-is`() {
        // No trailing Z, so it isn't an instant — no conversion may be applied.
        assertThat(untilLocalDate("20261231T235959", losAngeles))
            .isEqualTo(LocalDate.of(2026, 12, 31))
    }

    @Test
    fun `garbage returns null rather than throwing`() {
        assertThat(untilLocalDate("", berlin)).isNull()
        assertThat(untilLocalDate("nonsense", berlin)).isNull()
        assertThat(untilLocalDate("2026", berlin)).isNull()
        assertThat(untilLocalDate("20261340", berlin)).isNull()
    }
}

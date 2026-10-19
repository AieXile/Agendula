package de.jeanlucmakiola.agendula.domain.ical

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** RFC 5545 §3.3.6 `dur-value`, as alarm triggers use it. */
class ICalDurationTest {

    @Test
    fun `the forms other clients write`() {
        assertThat(ICalValues.parseDuration("-PT15M")).isEqualTo(-900L)
        assertThat(ICalValues.parseDuration("+PT5M")).isEqualTo(300L)
        assertThat(ICalValues.parseDuration("PT0S")).isEqualTo(0L)
        assertThat(ICalValues.parseDuration("P1W")).isEqualTo(7 * 86_400L)
        assertThat(ICalValues.parseDuration("-P1DT2H")).isEqualTo(-(86_400L + 7_200L))
        assertThat(ICalValues.parseDuration("-P0DT0H15M0S")).isEqualTo(-900L)
        assertThat(ICalValues.parseDuration("-PT90S")).isEqualTo(-90L)
    }

    @Test
    fun `what is not a duration is null`() {
        listOf("", "P", "PT", "-P1DT", "15M", "PT15", "P1H", "20261001T090000Z", "P99999999999999999999D")
            .forEach { assertThat(ICalValues.parseDuration(it)).isNull() }
    }

    @Test
    fun `written in days and time, and read back the same`() {
        assertThat(ICalValues.formatDuration(-900)).isEqualTo("-PT15M")
        assertThat(ICalValues.formatDuration(0)).isEqualTo("PT0S")
        assertThat(ICalValues.formatDuration(-86_400)).isEqualTo("-P1D")
        assertThat(ICalValues.formatDuration(-(86_400L + 5_400L))).isEqualTo("-P1DT1H30M")
        listOf(-900L, 0L, -86_400L, -93_784L, 3_600L).forEach {
            assertThat(ICalValues.parseDuration(ICalValues.formatDuration(it))).isEqualTo(it)
        }
    }
}

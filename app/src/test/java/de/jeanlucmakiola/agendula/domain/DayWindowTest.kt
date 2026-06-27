package de.jeanlucmakiola.agendula.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.time.Instant

class DayWindowTest {

    @Test
    fun `today spans exactly 24h in UTC and contains now`() {
        val zone = ZoneId.of("UTC")
        val now = Instant.fromEpochMilliseconds(1_750_000_000_000L)
        val (start, end) = DayWindow.today(now, zone)

        assertThat(end.toEpochMilliseconds() - start.toEpochMilliseconds()).isEqualTo(86_400_000L)
        assertThat(now >= start).isTrue()
        assertThat(now < end).isTrue()
        // In UTC, midnight aligns to a multiple of one day since the epoch.
        assertThat(start.toEpochMilliseconds() % 86_400_000L).isEqualTo(0L)
    }

    @Test
    fun `start is local midnight in a non-UTC zone`() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = Instant.fromEpochMilliseconds(1_750_000_000_000L)
        val (start, end) = DayWindow.today(now, zone)

        val startLocal = java.time.Instant.ofEpochMilli(start.toEpochMilliseconds()).atZone(zone)
        assertThat(startLocal.hour).isEqualTo(0)
        assertThat(startLocal.minute).isEqualTo(0)
        assertThat(now >= start).isTrue()
        assertThat(now < end).isTrue()
    }
}

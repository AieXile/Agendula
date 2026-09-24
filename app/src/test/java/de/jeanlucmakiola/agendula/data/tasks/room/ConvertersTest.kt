package de.jeanlucmakiola.agendula.data.tasks.room

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.priorityFromICal
import de.jeanlucmakiola.agendula.domain.toICal
import de.jeanlucmakiola.agendula.domain.TaskStatus
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class ConvertersTest {

    @Test
    fun `round-trips an instant through epoch millis`() {
        val value = Instant.fromEpochMilliseconds(1_700_000_000_123)

        val stored = Converters.instantToMillis(value)

        assertThat(stored).isEqualTo(1_700_000_000_123)
        assertThat(Converters.instantFromMillis(stored)).isEqualTo(value)
    }

    @Test
    fun `maps null time both ways`() {
        assertThat(Converters.instantToMillis(null)).isNull()
        assertThat(Converters.instantFromMillis(null)).isNull()
    }

    @Test
    fun `round-trips every status through the domain encoding`() {
        TaskStatus.entries.forEach { status ->
            assertThat(Converters.statusFrom(Converters.statusToInt(status))).isEqualTo(status)
        }
    }

    @Test
    fun `priority is stored raw, so an off-bucket value survives`() {
        // There is no priority converter on purpose: PRIORITY:3 is a legitimate
        // value a server can send, and Priority buckets 1..4 into HIGH. Bucketing
        // on the way in would rewrite it as 1 and lose it on the next round-trip.
        assertThat(priorityFromICal(3)).isEqualTo(Priority.HIGH)
        assertThat(Priority.HIGH.toICal()).isEqualTo(1)
    }

    @Test
    fun `round-trips an alarm reference and falls back on an unknown one`() {
        AlarmReference.entries.forEach { reference ->
            assertThat(Converters.alarmReferenceFrom(Converters.alarmReferenceToString(reference)))
                .isEqualTo(reference)
        }
        assertThat(Converters.alarmReferenceFrom("NONSENSE")).isEqualTo(AlarmReference.DUE)
    }
}

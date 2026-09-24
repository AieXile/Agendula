package de.jeanlucmakiola.agendula.data.reminders

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReminderDiffTest {

    private val a = ScheduledReminder(1, 1_000)
    private val b = ScheduledReminder(2, 2_000)
    private val c = ScheduledReminder(3, 3_000)

    @Test
    fun `an armed alarm that is still wanted is left alone`() {
        val diff = ReminderDiff.of(previous = setOf(a, b), desired = setOf(a, c)) { true }
        assertThat(diff.keep).containsExactly(a)
        assertThat(diff.cancel).containsExactly(b)
        assertThat(diff.arm).containsExactly(c)
    }

    @Test
    fun `alarms the system forgot are armed again, although the store still lists them`() {
        // A reboot or force-stop: the store came through, the alarms did not.
        val diff = ReminderDiff.of(previous = setOf(a, b), desired = setOf(a, b)) { false }
        assertThat(diff.arm).containsExactly(a, b)
        assertThat(diff.keep).isEmpty()
        assertThat(diff.cancel).isEmpty()
    }

    @Test
    fun `only the missing ones are re-armed`() {
        val diff = ReminderDiff.of(previous = setOf(a, b), desired = setOf(a, b)) { it == a }
        assertThat(diff.arm).containsExactly(b)
        assertThat(diff.keep).containsExactly(a)
    }

    @Test
    fun `a reminder that already fired is not armed again`() {
        val diff = ReminderDiff.of(
            previous = setOf(a),
            desired = setOf(a),
            fired = setOf(a),
        ) { false }
        assertThat(diff.arm).isEmpty()
        assertThat(diff.keep).containsExactly(a)
    }

    @Test
    fun `store entries round-trip, and junk is skipped`() {
        val encoded = ScheduledReminderStore.encode(setOf(a, b))
        assertThat(encoded.mapNotNull(ScheduledReminderStore::decode)).containsExactly(a, b)
        val withOccurrence = ScheduledReminder(4, 4_000, occurrenceStart = 3_000)
        assertThat(ScheduledReminderStore.encode(setOf(withOccurrence)).map(ScheduledReminderStore::decode))
            .containsExactly(withOccurrence)
        // Written before occurrences were recorded.
        assertThat(ScheduledReminderStore.decode("4|4000")).isEqualTo(ScheduledReminder(4, 4_000))
        assertThat(ScheduledReminderStore.decode("x|1")).isNull()
        assertThat(ScheduledReminderStore.decode("1")).isNull()
    }
}

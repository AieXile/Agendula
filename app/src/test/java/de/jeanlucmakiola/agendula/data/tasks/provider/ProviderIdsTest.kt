package de.jeanlucmakiola.agendula.data.tasks.provider

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class ProviderIdsTest {

    @Test
    fun `an instance id gives back its series`() {
        val id = ProviderIds.instance(1234, Instant.parse("2026-10-15T09:00:00Z"))

        assertThat(ProviderIds.instanceSeries(id)).isEqualTo(1234L)
        assertThat(ProviderIds.instanceSeconds(id)).isEqualTo(Instant.parse("2026-10-15T09:00:00Z").epochSeconds)
        assertThat(id).isGreaterThan(0L)
    }

    @Test
    fun `a plain task's instance has no time`() {
        assertThat(ProviderIds.instanceSeconds(ProviderIds.instance(7, null))).isEqualTo(0L)
    }

    @Test
    fun `occurrences of one series and of different series never share an id`() {
        val at = Instant.parse("2026-10-15T09:00:00Z")

        val ids = listOf(
            ProviderIds.instance(1, at),
            ProviderIds.instance(1, Instant.fromEpochSeconds(at.epochSeconds + 1)),
            ProviderIds.instance(2, at),
            ProviderIds.instance(1, null),
        )

        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `alarm ids are even and tag ids odd`() {
        val alarm = ProviderIds.alarm(42)
        val tags = ProviderIds.categories(42, listOf("a", "b"))

        assertThat(ProviderIds.isAlarm(alarm)).isTrue()
        assertThat(ProviderIds.alarmRow(alarm)).isEqualTo(42L)
        tags.values.forEach {
            assertThat(ProviderIds.isAlarm(it)).isFalse()
            assertThat(ProviderIds.categoryTask(it)).isEqualTo(42L)
        }
    }

    @Test
    fun `a tag's id depends on its task and spelling only`() {
        val alone = ProviderIds.categories(5, listOf("home"))
        val among = ProviderIds.categories(5, listOf("work", "home", "errand"))

        assertThat(among["home"]).isEqualTo(alone["home"])
        assertThat(ProviderIds.categories(6, listOf("home"))["home"]).isNotEqualTo(alone["home"])
    }

    @Test
    fun `two spellings with one hash still get two ids`() {
        // "Aa" and "BB" share String.hashCode().
        val ids = ProviderIds.categories(5, listOf("Aa", "BB"))

        assertThat(ids.values).containsNoDuplicates()
        assertThat(ids.values.map(ProviderIds::categoryTask)).containsExactly(5L, 5L)
    }

    @Test
    fun `colliding tags keep their ids whatever their order`() {
        val hash = { _: String -> 7L }

        assertThat(ProviderIds.categories(5, listOf("b", "a"), hash))
            .isEqualTo(ProviderIds.categories(5, listOf("a", "b"), hash))
    }

    @Test
    fun `colliding tags stay inside their task's bits`() {
        val ids = ProviderIds.categories(5, listOf("a", "b", "c"), hash = { 0x7FFFFFFFL })

        assertThat(ids.values).containsNoDuplicates()
        ids.values.forEach {
            assertThat(ProviderIds.categoryTask(it)).isEqualTo(5L)
            assertThat(ProviderIds.isAlarm(it)).isFalse()
        }
    }

    @Test
    fun `tags of neighbouring tasks never share an id`() {
        val names = (1..200).map { "tag$it" }

        val ids = (1L..50L).flatMap { ProviderIds.categories(it, names).values }

        assertThat(ids).containsNoDuplicates()
    }
}


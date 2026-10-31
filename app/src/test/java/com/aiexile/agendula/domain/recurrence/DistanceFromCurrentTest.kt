package com.aiexile.agendula.domain.recurrence

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class DistanceFromCurrentTest {

    private fun at(text: String) = Instant.parse(text)

    private val occurrences = listOf(
        at("2025-01-05T09:00:00Z"),
        at("2025-01-06T09:00:00Z"),
        at("2025-01-07T09:00:00Z"),
        at("2025-01-08T09:00:00Z"),
    )

    @Test
    fun `the current occurrence is the first one at or after now`() {
        val index = RecurrenceExpander.currentOccurrenceIndex(occurrences, at("2025-01-06T10:00:00Z"))
        assertThat(index).isEqualTo(2)
    }

    @Test
    fun `an occurrence exactly at now is the current one`() {
        val index = RecurrenceExpander.currentOccurrenceIndex(occurrences, at("2025-01-06T09:00:00Z"))
        assertThat(index).isEqualTo(1)
    }

    @Test
    fun `past occurrences count down and later ones count up`() {
        val distances = RecurrenceExpander.distancesFromCurrent(occurrences, at("2025-01-06T10:00:00Z"))
        assertThat(distances).containsExactly(-2, -1, 0, 1).inOrder()
    }

    @Test
    fun `a series entirely in the future is current at its first occurrence`() {
        val distances = RecurrenceExpander.distancesFromCurrent(occurrences, at("2024-12-01T00:00:00Z"))
        assertThat(distances).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test
    fun `a series entirely in the past is current at its last occurrence`() {
        val distances = RecurrenceExpander.distancesFromCurrent(occurrences, at("2026-01-01T00:00:00Z"))
        assertThat(distances).containsExactly(-3, -2, -1, 0).inOrder()
    }

    @Test
    fun `exactly one occurrence is ever the current one`() {
        val distances = RecurrenceExpander.distancesFromCurrent(occurrences, at("2025-01-07T00:00:00Z"))
        assertThat(distances.count { it == 0 }).isEqualTo(1)
    }

    @Test
    fun `an empty series has no distances`() {
        assertThat(RecurrenceExpander.distancesFromCurrent(emptyList(), at("2025-01-01T00:00:00Z"))).isEmpty()
        assertThat(RecurrenceExpander.currentOccurrenceIndex(emptyList(), at("2025-01-01T00:00:00Z"))).isEqualTo(-1)
    }
}

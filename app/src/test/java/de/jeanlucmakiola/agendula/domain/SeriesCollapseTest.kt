package de.jeanlucmakiola.agendula.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class SeriesCollapseTest {

    private fun occurrence(
        day: Int,
        distance: Int,
        status: TaskStatus = TaskStatus.NEEDS_ACTION,
        rowId: Long = 7,
    ): Task {
        val at = Instant.fromEpochMilliseconds(day * 86_400_000L)
        return testTask(id = rowId, status = status, due = at).copy(
            isRecurring = rowId == 7L,
            occurrenceStart = at,
            distanceFromCurrent = distance,
            seriesId = 7,
        )
    }

    @Test
    fun `a daily series shows one open occurrence, not a year of them`() {
        val series = (-5..5).map { occurrence(day = 100 + it, distance = it) }

        val visible = SeriesCollapse.visible(series)

        assertThat(visible.map { it.distanceFromCurrent }).containsExactly(-1)
    }

    @Test
    fun `yesterday done leaves today as the one to do`() {
        val series = listOf(
            occurrence(day = 99, distance = -1, status = TaskStatus.COMPLETED, rowId = 20),
            occurrence(day = 100, distance = 0),
            occurrence(day = 101, distance = 1),
        )

        val visible = SeriesCollapse.visible(series)

        assertThat(visible.map { it.taskId to it.distanceFromCurrent })
            .containsExactly(20L to -1, 7L to 0)
    }

    @Test
    fun `a closed series shows once`() {
        val series = (-2..2).map { occurrence(day = 100 + it, distance = it, status = TaskStatus.COMPLETED) }

        assertThat(SeriesCollapse.visible(series)).hasSize(1)
    }

    @Test
    fun `plain tasks pass through untouched`() {
        val plain = testTask(id = 1)

        assertThat(SeriesCollapse.visible(listOf(plain))).containsExactly(plain)
    }
}

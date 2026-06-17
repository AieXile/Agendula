package de.jeanlucmakiola.floret.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ModelsTest {

    @Test
    fun `priority maps from iCalendar buckets`() {
        assertThat(priorityFromICal(null)).isEqualTo(Priority.NONE)
        assertThat(priorityFromICal(0)).isEqualTo(Priority.NONE)
        assertThat(priorityFromICal(1)).isEqualTo(Priority.HIGH)
        assertThat(priorityFromICal(4)).isEqualTo(Priority.HIGH)
        assertThat(priorityFromICal(5)).isEqualTo(Priority.MEDIUM)
        assertThat(priorityFromICal(6)).isEqualTo(Priority.LOW)
        assertThat(priorityFromICal(9)).isEqualTo(Priority.LOW)
    }

    @Test
    fun `priority maps back to representative iCalendar values`() {
        assertThat(Priority.NONE.toICal()).isEqualTo(0)
        assertThat(Priority.HIGH.toICal()).isEqualTo(1)
        assertThat(Priority.MEDIUM.toICal()).isEqualTo(5)
        assertThat(Priority.LOW.toICal()).isEqualTo(9)
    }

    @Test
    fun `status round-trips through its integer code`() {
        TaskStatus.entries.forEach { status ->
            assertThat(statusFromInt(status.toInt())).isEqualTo(status)
        }
        assertThat(statusFromInt(null)).isEqualTo(TaskStatus.NEEDS_ACTION)
    }
}

package de.jeanlucmakiola.agendula.data.prefs

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.domain.SmartList
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.testTask
import org.junit.jupiter.api.Test

class SettingsVisibilityTest {

    private val tasks = listOf(testTask(id = 1, listId = 1), testTask(id = 2, listId = 2))
    private val settings = Settings(hiddenFromSmartLists = setOf(2L))

    @Test
    fun `a smart list leaves out a hidden list`() {
        assertThat(settings.visibleIn(TaskFilter.Smart(SmartList.ALL), tasks).map { it.taskId }).containsExactly(1L)
    }

    @Test
    fun `the hidden list still shows its own tasks`() {
        assertThat(settings.visibleIn(TaskFilter.OfList(2), tasks)).isEqualTo(tasks)
    }
}

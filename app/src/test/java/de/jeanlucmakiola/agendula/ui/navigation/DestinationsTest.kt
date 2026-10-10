package de.jeanlucmakiola.agendula.ui.navigation

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.domain.TaskFilter
import org.junit.jupiter.api.Test

class DestinationsTest {

    @Test
    fun `task detail carries the occurrence only when there is one`() {
        assertThat(Dest.TaskDetail.build(7)).isEqualTo("taskDetail/7")
        assertThat(Dest.TaskDetail.build(7, 1_700_000_000_000)).isEqualTo("taskDetail/7?occ=1700000000000")
        assertThat(Dest.TaskDetail.route).isEqualTo("taskDetail/{taskId}?occ={occ}")
    }

    @Test
    fun `settings opens on the hub unless a section is named`() {
        assertThat(Dest.Settings.build()).isEqualTo(Dest.SETTINGS)
        assertThat(Dest.Settings.build(Dest.Settings.SECTION_ACCOUNTS)).isEqualTo("settings?section=Accounts")
    }

    @Test
    fun `a saved filter routes by id and comes back from its arguments`() {
        assertThat(Dest.TaskList.build(TaskFilter.Saved(5))).isEqualTo("taskList?filterId=5")
        assertThat(Dest.TaskList.filterOf(smart = null, listId = Dest.NO_ID, filterId = 5)).isEqualTo(TaskFilter.Saved(5))
        assertThat(Dest.FilterEdit.build()).isEqualTo("filterEdit")
        assertThat(Dest.FilterEdit.build(5)).isEqualTo("filterEdit?filterId=5")
        assertThat(Dest.TaskEdit.buildNew(filterId = 5)).isEqualTo("taskEdit?filterId=5")
    }
}

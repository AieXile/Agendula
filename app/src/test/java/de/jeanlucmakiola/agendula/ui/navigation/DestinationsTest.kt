package de.jeanlucmakiola.agendula.ui.navigation

import com.google.common.truth.Truth.assertThat
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
}

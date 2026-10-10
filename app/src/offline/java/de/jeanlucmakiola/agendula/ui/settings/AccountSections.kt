package de.jeanlucmakiola.agendula.ui.settings

import androidx.compose.runtime.Composable

/** No accounts in the `offline` flavor; the hub hides the row that leads here. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AccountSections(
    section: SettingsSection?,
    initialAccountId: Long?,
    onSection: (SettingsSection?) -> Unit,
) = Unit

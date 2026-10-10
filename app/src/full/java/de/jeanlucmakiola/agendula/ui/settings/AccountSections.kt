package de.jeanlucmakiola.agendula.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jeanlucmakiola.agendula.data.tasks.room.AccountEntity
import de.jeanlucmakiola.agendula.ui.accounts.AccountDetailScreen
import de.jeanlucmakiola.agendula.ui.accounts.AccountsScreen
import de.jeanlucmakiola.agendula.ui.accounts.AccountsViewModel
import de.jeanlucmakiola.agendula.ui.accounts.add.AddAccountScreen
import de.jeanlucmakiola.agendula.ui.accounts.add.AddAccountViewModel

/** Settings → Accounts and the screens under it, layered over the hub. */
@Composable
internal fun AccountSections(
    section: SettingsSection?,
    initialAccountId: Long?,
    onSection: (SettingsSection?) -> Unit,
) {
    // Hoisted so the add flow can refresh the list it returns to.
    val accountsViewModel: AccountsViewModel = hiltViewModel()
    // Shared with the add flow's own lookup, so "sign in again" can prefill it.
    val addAccountViewModel: AddAccountViewModel = hiltViewModel()
    val signInAgain: (AccountEntity) -> Unit = { account ->
        addAccountViewModel.startReauthentication(account.id, account.principalUrl, account.username)
        onSection(SettingsSection.AddAccount)
    }
    // Which account the detail screen is showing; the section alone cannot say.
    var openAccount by rememberSaveable(initialAccountId) { mutableStateOf(initialAccountId) }

    // Accounts stays composed under Add account, for the same reason Storage
    // stays composed under Export: the deeper screen slides over it.
    val accountsOpen = section == SettingsSection.Accounts ||
        section?.parent == SettingsSection.Accounts
    SlideInSection(visible = accountsOpen) {
        AccountsScreen(
            onAddAccount = {
                // A sign-in abandoned half-way must not turn this into one.
                if (addAccountViewModel.state.value.reauthenticating) addAccountViewModel.onStartOver()
                onSection(SettingsSection.AddAccount)
            },
            onSignInAgain = signInAgain,
            onOpenAccount = {
                openAccount = it
                onSection(SettingsSection.Account)
            },
            onOpenStorage = { onSection(SettingsSection.Storage) },
            onBack = { onSection(null) },
            viewModel = accountsViewModel,
        )
    }
    SlideInSection(visible = section == SettingsSection.Account) {
        openAccount?.let { id ->
            AccountDetailScreen(
                accountId = id,
                onBack = { onSection(SettingsSection.Accounts) },
                onRemoved = { onSection(SettingsSection.Accounts) },
                onSignInAgain = signInAgain,
                onOpenStorage = { onSection(SettingsSection.Storage) },
                viewModel = accountsViewModel,
            )
        }
    }
    SlideInSection(visible = section == SettingsSection.AddAccount) {
        AddAccountScreen(
            // The list stays composed underneath, so nothing re-runs its
            // init and OnResume never fires on a section change. It no longer
            // needs to: the accounts come from an observed query, so a new
            // account — and every later sync — arrives on its own.
            onDone = { onSection(SettingsSection.Accounts) },
            onBack = { onSection(SettingsSection.Accounts) },
            viewModel = addAccountViewModel,
        )
    }
}

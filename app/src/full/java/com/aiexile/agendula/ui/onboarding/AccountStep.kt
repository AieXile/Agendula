package com.aiexile.agendula.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiexile.agendula.ui.accounts.add.ADD_ACCOUNT_STEPS
import com.aiexile.agendula.ui.accounts.add.AddAccountScreen
import com.aiexile.agendula.ui.accounts.add.AddAccountViewModel

/** The inline wizard's shortest length, before a provider is picked. */
internal const val ACCOUNT_STEP_SLOTS = ADD_ACCOUNT_STEPS

/** [OnboardingStep.Account]: the add-account wizard, given the whole screen. */
@Composable
internal fun AccountStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    // The same instance the wizard resolves for itself, so its length can be
    // read here without keeping a second copy of its state.
    val account: AddAccountViewModel = hiltViewModel()
    val accountState by account.state.collectAsStateWithLifecycle()
    // ⚠️ The wizard grows a step for a provider that needs an app password
    // minted first, and the outer bar has to grow with it — otherwise first
    // run counts one flow while the screen inside it counts another.
    LaunchedEffect(accountState.totalSteps) {
        viewModel.onAccountStepsChanged(accountState.totalSteps)
    }
    AddAccountScreen(
        onDone = { viewModel.onAccountFinished(added = true) },
        onBack = { viewModel.onAccountFinished(added = false) },
        stepOffset = state.position - 1,
        totalSteps = state.total,
        viewModel = account,
    )
}

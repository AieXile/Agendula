package de.jeanlucmakiola.agendula.ui.onboarding

import androidx.compose.runtime.Composable

internal const val ACCOUNT_STEP_SLOTS = 0

/** Never reached: without sync the flow has no [OnboardingStep.SyncOffer] to lead here. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AccountStep(state: OnboardingUiState, viewModel: OnboardingViewModel) = Unit

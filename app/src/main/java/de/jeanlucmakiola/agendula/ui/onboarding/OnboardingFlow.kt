package de.jeanlucmakiola.agendula.ui.onboarding

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.floret.components.OnboardingProgress
import de.jeanlucmakiola.floret.components.OnboardingScaffold
import de.jeanlucmakiola.floret.components.OnboardingSpace

/**
 * First run, end to end: welcome, reminders, the offer to sync, and then the
 * loose ends that offer leaves — a list, a copy, the quick-add bar. Which steps
 * run is [OnboardingViewModel]'s business; this only draws the one it is on.
 *
 * [OnboardingStep.Account] hands the whole screen to the add-account wizard,
 * which already speaks `OnboardingScaffold` — it just needs to be told where in
 * *this* flow its own steps sit, so the progress stays one bar.
 */
@Composable
fun OnboardingFlow(
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.step == OnboardingStep.Account) {
        AccountStep(state, viewModel)
        return
    }

    val chrome = StepChrome(state, onBack = viewModel::back, modifier = modifier)
    // System back steps back through the flow rather than leaving the app mid-way.
    BackHandler(enabled = state.canGoBack) { viewModel.back() }

    when (state.step) {
        OnboardingStep.Welcome -> WelcomeStep(chrome, onContinue = viewModel::onWelcomeDone)
        OnboardingStep.Reminders -> RemindersStep(chrome, onAnswered = viewModel::onRemindersAnswered)
        OnboardingStep.ExactAlarms -> ExactAlarmStep(chrome, onContinue = viewModel::onExactAlarmsDone)
        OnboardingStep.SyncOffer -> SyncOfferStep(chrome, onAnswered = viewModel::onSyncAnswered)
        OnboardingStep.FirstList -> FirstListStep(chrome, onCreate = viewModel::createFirstList)
        OnboardingStep.Backup -> BackupStep(chrome, onContinue = viewModel::onBackupDone)
        OnboardingStep.QuickAdd -> QuickAddStep(
            chrome = chrome,
            enabled = state.bottomAddBar,
            onToggle = viewModel::setBottomAddBar,
            onContinue = viewModel::finish,
        )
        OnboardingStep.Account -> Unit
    }
}

/**
 * The parts of a step's screen that belong to the flow rather than the step:
 * where it sits in the progress, and whether there is a way back from it. Passed
 * down so a step supplies only what is its own.
 */
@Immutable
internal data class StepChrome(
    val state: OnboardingUiState,
    val onBack: () -> Unit,
    val modifier: Modifier,
)

/** The family's onboarding shell with the flow's [chrome] already filled in. */
@Composable
internal fun StepScaffold(
    chrome: StepChrome,
    hero: @Composable () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    contentPadding: Dp = OnboardingSpace.md,
    topSpacing: Dp = OnboardingSpace.md,
    scrollingActions: Boolean = false,
    body: @Composable ColumnScope.() -> Unit,
) {
    OnboardingScaffold(
        modifier = chrome.modifier,
        hero = hero,
        progress = {
            OnboardingProgress(
                step = chrome.state.position,
                total = chrome.state.total,
                label = stringResource(
                    R.string.add_account_step_of,
                    chrome.state.position,
                    chrome.state.total,
                ),
            )
        },
        navigationIcon = if (!chrome.state.canGoBack) {
            null
        } else {
            {
                IconButton(onClick = chrome.onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            }
        },
        contentPadding = contentPadding,
        topSpacing = topSpacing,
        scrollingActions = scrollingActions,
        actions = actions,
        body = body,
    )
}

/**
 * A step's opening lines: an optional eyebrow, the headline, and one paragraph,
 * centred above whatever the step asks for. [horizontalPadding] is for a step
 * that passed `contentPadding = 0` because its own content carries the inset.
 */
@Composable
internal fun ColumnScope.StepHeader(
    title: String,
    body: String,
    eyebrow: String? = null,
    horizontalPadding: Dp = 0.dp,
) {
    if (eyebrow != null) {
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(OnboardingSpace.xs))
    }
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = horizontalPadding),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = horizontalPadding),
    )
}

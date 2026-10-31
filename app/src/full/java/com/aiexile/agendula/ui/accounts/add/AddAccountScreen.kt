package com.aiexile.agendula.ui.accounts.add

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiexile.agendula.R
import com.aiexile.agendula.ui.accounts.ProviderLogo
import de.jeanlucmakiola.floret.components.OnboardingProgress
import de.jeanlucmakiola.floret.components.OnboardingScaffold
import de.jeanlucmakiola.floret.components.OnboardingSpace

/**
 * Adding a CalDAV account: one flow, one back-stack entry.
 *
 * A stepper rather than several destinations, because the steps are not
 * independently reachable — you cannot pick lists before signing in, and going
 * "back" from the browser step means abandoning a server-side flow rather than
 * popping a screen. The steps that *have* written nothing do step back, in the
 * flow rather than out of it; [AddAccountViewModel.onBackWithin] decides which,
 * and the shell falls out only when it says there is nowhere left to go. The
 * back arrow and the system back gesture are the same action and run the same
 * code — a stepper whose gesture skips the steps is a stepper with one way in
 * and no way back.
 *
 * [stepOffset] and [totalSteps] let a longer flow host this one inline — first
 * run offers the account before it has finished onboarding — so the wizard's
 * four steps report their place in the *outer* progress rather than restarting
 * the count at one. Left at their defaults it stands alone.
 */
@Composable
internal fun AddAccountScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    stepOffset: Int = 0,
    // Null when the wizard stands alone: it then counts its own steps, which
    // vary by provider. A host that draws one bar for a longer flow passes the
    // whole denominator, having already asked the wizard how long it is.
    totalSteps: Int? = null,
    viewModel: AddAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.step) {
        if (state.step !is AddAccountStep.Done) return@LaunchedEffect
        onDone()
        // The ViewModel is scoped to the Settings back-stack entry and survives
        // this section being hidden, so a finished flow left at Done would bounce
        // the next "Add account" straight back out — and would reuse this
        // account's username and app password for the next one.
        viewModel.onStartOver()
    }

    // Custom Tabs needs three things beyond launchUrl: the <queries> entry in the
    // manifest (or provider detection silently finds nothing on API 30+), a
    // fallback for devices with no Custom Tabs browser at all — realistic on
    // GrapheneOS, CalyxOS and plain AOSP, which is disproportionately this app's
    // audience — and an explicit way back, since a dismissed tab returns nothing.
    LaunchedEffect(state.openInBrowser) {
        val url = state.openInBrowser ?: return@LaunchedEffect
        val uri = url.toString().toUri()
        // ⚠️ Neither failure may escape, and neither may be ignored. An
        // exception out of a LaunchedEffect takes the app down — and the outer
        // catch named only ActivityNotFoundException, so a SecurityException
        // from a locked-down profile did exactly that. Reporting the failure is
        // the other half: clearing openInBrowser regardless left the user on
        // "waiting for your browser" with a spinner and no browser, on the very
        // devices this fallback exists for.
        val launched = runCatching {
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        }.recoverCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.isSuccess
        if (launched) viewModel.onBrowserLaunched() else viewModel.onBrowserUnavailable()
    }

    // Backing out abandons a flow that may still be polling the server every two
    // seconds for the rest of its twenty-minute window.
    val abandon = {
        viewModel.onStartOver()
        onBack()
    }

    // One action, two ways to ask for it: step back inside the flow, and fall
    // out of it only when there is nowhere left to go.
    val goBack = { if (!viewModel.onBackWithin()) abandon() }

    // ⚠️ The system gesture has to be caught here, not left to the host. The
    // hosts only put the section away — `SettingsScreen` sets `section = parent`
    // — while this ViewModel is scoped to the Settings back-stack entry and
    // outlives that. So a gesture that fell through left the flow *loaded*:
    // reopening "Add account" landed back on the previous server's list picker,
    // still holding its username, password and collections. Worse in
    // `WaitingForBrowser`, where the two-second poll kept running with no screen
    // attached for the rest of the twenty-minute window, and a password minted
    // after that point was held unrevoked until Settings itself was left.
    // Consuming it means [AddAccountViewModel.onStartOver] runs on every exit.
    BackHandler(onBack = goBack)

    OnboardingScaffold(
        hero = { StepHero(state) },
        // Grouped rows and fields carry their own inset, so the column adds none.
        contentPadding = 0.dp,
        topSpacing = OnboardingSpace.md,
        progress = {
            val position = state.step.position(state.hasSetupStep)?.plus(stepOffset)
            val total = totalSteps ?: state.totalSteps
            if (position != null) {
                OnboardingProgress(
                    step = position,
                    total = total,
                    label = stringResource(R.string.add_account_step_of, position, total),
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = goBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        actions = { StepActions(state, viewModel) },
    ) {
        val fatal = state.fatal
        if (fatal != null) {
            Text(
                fatal.text(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            return@OnboardingScaffold
        }

        StepTitle(state.step)

        when (val step = state.step) {
            is AddAccountStep.ChooseProvider -> ProviderStep(step, viewModel)
            is AddAccountStep.PrepareAccess -> SetupStep(step)
            is AddAccountStep.EnterAddress -> AddressStep(step, viewModel)
            is AddAccountStep.Working -> WorkingStep(step)
            is AddAccountStep.EnterCredentials -> CredentialsStep(step, viewModel)
            is AddAccountStep.ConfirmBrowser -> ConfirmBrowserStep(step)
            is AddAccountStep.WaitingForBrowser -> BrowserStep(step)
            is AddAccountStep.ChooseLists -> ListsStep(step, viewModel)
            is AddAccountStep.Summary -> SummaryStep(step)
            AddAccountStep.Done -> Unit
        }

        // What is known about the chosen service, named before the attempt rather
        // than after a 401 the user cannot act on.
        //
        // ⚠️ Not on the errand step — that step *is* this, and drawing it again
        // underneath would print the same instructions twice. On the address step
        // it is the fallback for the one route that skips the errand: picking
        // "Other server" and then typing an address that turns out to be a
        // Fastmail or iCloud one.
        val note = when (state.step) {
            is AddAccountStep.ChooseProvider, is AddAccountStep.EnterAddress -> state.quirk
            else -> null
        }
        note?.let {
            Spacer(Modifier.height(OnboardingSpace.md))
            QuirkGuidance(it)
        }

        // Learned during the browser step and shown from there on, whichever
        // step follows: the address it blames is one the user has to go and
        // correct on the server, and it is just as true once the lists load.
        state.originMismatch?.let { mismatch ->
            QuirkNote(
                text = stringResource(
                    R.string.add_account_origin_mismatch,
                    mismatch.actual,
                    mismatch.expected,
                ),
            )
        }
    }
}

/**
 * Which of the flow's visible steps this is, or null for one that has no place.
 *
 * [hasSetup] shifts everything after the errand down by one, because the errand
 * is a step of its own rather than a screen borrowing the address's number.
 */
private fun AddAccountStep.position(hasSetup: Boolean): Int? {
    val errand = if (hasSetup) 1 else 0
    return when (this) {
        is AddAccountStep.ChooseProvider -> 1
        is AddAccountStep.PrepareAccess -> 2
        is AddAccountStep.EnterAddress -> 2 + errand
        // One slot, because only one of the three ever happens: a server either
        // hands the sign-in to a browser — asking first, where the URL needs
        // confirming — or asks for a password.
        is AddAccountStep.EnterCredentials,
        is AddAccountStep.ConfirmBrowser,
        is AddAccountStep.WaitingForBrowser,
        -> 3 + errand
        is AddAccountStep.ChooseLists -> 4 + errand
        // The receipt keeps the bar full rather than dropping it: the flow is
        // finished, and a chrome that vanishes on the last screen reads as a
        // step lost rather than a step done.
        is AddAccountStep.Summary -> ADD_ACCOUNT_STEPS + errand
        // Working is a moment inside whichever step spawned it, and Done is gone
        // before it draws — neither is a place the user can be.
        is AddAccountStep.Working, AddAccountStep.Done -> null
    }
}

/**
 * The mark for the step, in the family's tonal circle.
 *
 * Once a service has been chosen it wears *that service's* logo instead — the
 * same mark the accounts list will show — so the flow keeps saying what is being
 * set up rather than restating that an account is being added.
 */
@Composable
private fun StepHero(state: AddAccountUiState) {
    if (state.step is AddAccountStep.Summary) {
        ProviderLogo(state.step.provider, size = 72.dp)
        return
    }
    val chosen = (state.step as? AddAccountStep.EnterAddress)?.choice?.provider
        ?: (state.step as? AddAccountStep.PrepareAccess)?.choice?.provider
    if (chosen != null && state.fatal == null) {
        ProviderLogo(chosen, size = 72.dp)
        return
    }
    val icon = when {
        state.fatal != null -> Icons.Rounded.CloudOff
        state.step is AddAccountStep.EnterCredentials -> Icons.Rounded.Lock
        // The warning is the screen, so it is the mark too — the browser icon
        // would say "this is going fine", which is the opposite of the point.
        state.step is AddAccountStep.ConfirmBrowser -> Icons.Rounded.Warning
        state.step is AddAccountStep.WaitingForBrowser -> Icons.Rounded.OpenInBrowser
        state.step is AddAccountStep.ChooseLists -> Icons.Rounded.Checklist
        else -> Icons.Rounded.CloudSync
    }
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(34.dp),
        )
    }
}

/** Title and one line of explanation, centred above whatever the step asks for. */
@Composable
private fun StepTitle(step: AddAccountStep) {
    val (title, body) = when (step) {
        is AddAccountStep.ChooseProvider ->
            R.string.add_account_provider_title to R.string.add_account_provider_body
        is AddAccountStep.PrepareAccess ->
            step.quirk.setupTitle to R.string.add_account_setup_body
        is AddAccountStep.EnterAddress -> if (step.manualServer) {
            R.string.add_account_server_title to R.string.add_account_server_fallback_body
        } else if (step.asksForEmail) {
            R.string.add_account_email_title to R.string.add_account_email_body
        } else {
            R.string.add_account_server_title to R.string.add_account_server_body
        }
        is AddAccountStep.EnterCredentials ->
            R.string.add_account_credentials_title to R.string.add_account_credentials_body
        is AddAccountStep.ConfirmBrowser ->
            R.string.add_account_browser_confirm_title to R.string.add_account_browser_confirm_body
        is AddAccountStep.ChooseLists ->
            R.string.add_account_lists_title to R.string.add_account_lists_body
        is AddAccountStep.Summary ->
            R.string.add_account_summary_title to R.string.add_account_summary_body
        else -> return
    }
    Text(
        stringResource(title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    Spacer(Modifier.height(OnboardingSpace.lg))
}

/**
 * The step's call to action, pinned at the bottom the way the onboarding shell
 * puts it.
 *
 * One primary action per step, always in the same place, so the flow reads as
 * one screen advancing rather than five different ones. A step that commits
 * nothing renders no button rather than a disabled one.
 */
@Composable
private fun ColumnScope.StepActions(state: AddAccountUiState, viewModel: AddAccountViewModel) {
    if (state.fatal != null) {
        PrimaryAction(
            label = stringResource(R.string.add_account_start_over),
            enabled = true,
            onClick = viewModel::onStartOver,
        )
        return
    }

    when (val step = state.step) {
        // ⚠️ No action at all. Tapping a row *is* the choice and advances on the
        // spot, so a Continue here would be a second press for the same decision
        // — and a disabled one, before anything is picked, that looks like the
        // screen is broken.
        is AddAccountStep.ChooseProvider -> Unit

        is AddAccountStep.PrepareAccess -> PrimaryAction(
            label = stringResource(R.string.add_account_continue),
            enabled = true,
            onClick = viewModel::onSetupAcknowledged,
        )

        is AddAccountStep.EnterAddress -> PrimaryAction(
            label = stringResource(R.string.add_account_continue),
            enabled = step.input.isNotBlank(),
            onClick = viewModel::onAddressSubmitted,
        )

        is AddAccountStep.EnterCredentials -> PrimaryAction(
            label = stringResource(R.string.add_account_sign_in),
            enabled = step.username.isNotBlank() && step.password.isNotEmpty(),
            onClick = viewModel::onCredentialsSubmitted,
        )

        is AddAccountStep.ChooseLists -> PrimaryAction(
            // The count is the feedback: a disabled button with no explanation
            // is the commonest way a picker looks broken.
            label = if (step.selected.isEmpty()) {
                stringResource(R.string.add_account_no_lists_selected)
            } else {
                stringResource(R.string.add_account_save)
            },
            enabled = step.selected.isNotEmpty(),
            onClick = viewModel::onSave,
        )

        is AddAccountStep.Summary -> PrimaryAction(
            label = stringResource(R.string.add_account_summary_done),
            enabled = true,
            onClick = viewModel::onSummaryDone,
        )

        // The way forward stays primary: both causes are legitimate behind a
        // reverse proxy, and refusing outright would make Login Flow v2 unusable
        // for a large share of self-hosted installs. The password route is the
        // secondary, exactly as it is one step later.
        is AddAccountStep.ConfirmBrowser -> {
            PrimaryAction(
                label = stringResource(R.string.add_account_browser_open),
                enabled = true,
                onClick = viewModel::onBrowserConfirmed,
            )
            TextButton(
                onClick = viewModel::onBrowserCancelled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.add_account_browser_use_password)) }
        }

        is AddAccountStep.WaitingForBrowser -> {
            // A failed flow is retried by default. With no browser a retry
            // cannot help; while the server throttles or is in maintenance an
            // immediate one only adds to it, so a password comes first.
            val usePassword = @Composable { primary: Boolean ->
                if (primary) {
                    PrimaryAction(
                        label = stringResource(R.string.add_account_browser_use_password),
                        enabled = true,
                        onClick = viewModel::onBrowserCancelled,
                    )
                } else {
                    TextButton(onClick = viewModel::onBrowserCancelled, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.add_account_browser_use_password))
                    }
                }
            }
            val retry = @Composable { primary: Boolean ->
                if (primary) {
                    PrimaryAction(
                        label = stringResource(R.string.add_account_browser_retry),
                        enabled = true,
                        onClick = viewModel::onBrowserRetry,
                    )
                } else {
                    TextButton(onClick = viewModel::onBrowserRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.add_account_browser_retry))
                    }
                }
            }
            when (step.error) {
                null -> usePassword(false)
                AddAccountMessage.BrowserUnavailable -> usePassword(true)
                AddAccountMessage.BrowserRateLimited, AddAccountMessage.BrowserMaintenance -> {
                    usePassword(true)
                    retry(false)
                }
                else -> {
                    retry(true)
                    usePassword(false)
                }
            }
        }

        is AddAccountStep.Working, AddAccountStep.Done -> Unit
    }
}


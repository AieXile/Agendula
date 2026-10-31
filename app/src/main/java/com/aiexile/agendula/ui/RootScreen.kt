package com.aiexile.agendula.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiexile.agendula.R
import com.aiexile.agendula.ui.common.OnResume
import com.aiexile.agendula.data.tasks.ProviderStatus
import com.aiexile.agendula.ui.navigation.AgendulaNavHost
import com.aiexile.agendula.ui.navigation.ReadableWidth
import com.aiexile.agendula.ui.navigation.NavRequest
import com.aiexile.agendula.ui.onboarding.OnboardingFlow
import com.aiexile.agendula.ui.onboarding.OnboardingViewModel
import com.aiexile.agendula.ui.onboarding.SquircleHero
import com.aiexile.agendula.ui.permission.PermissionViewModel
import de.jeanlucmakiola.floret.components.OnboardingScaffold
import de.jeanlucmakiola.floret.components.OnboardingSpace

/**
 * App root: gates on the tasks-provider permission, then hands off to
 * [AgendulaNavHost] (lists → task list → detail / edit).
 */
@Composable
fun RootScreen(
    modifier: Modifier = Modifier,
    navRequest: NavRequest? = null,
    onNavRequestConsumed: () -> Unit = {},
    permissionViewModel: PermissionViewModel = hiltViewModel(),
) {
    val permission by permissionViewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionViewModel.refresh() }

    // Re-check on every resume, not just after the in-app request: the user may
    // have granted the permission (or installed a provider) in system Settings and
    // come back, and otherwise the gate would hold until the process restarts.
    OnResume { permissionViewModel.refresh() }

    // Neither gate can show in OWN mode (that store is always READY), so the way
    // out of one is always our own store. Without it a user whose provider app
    // went away is held on this screen with Settings behind it.
    val fallback = stringResource(R.string.onboarding_use_own_store)

    when (permission.status) {
        ProviderStatus.NO_PROVIDER -> Gate(
            modifier = modifier,
            icon = Icons.Rounded.CloudOff,
            title = stringResource(R.string.onboarding_no_provider_title),
            body = stringResource(R.string.onboarding_no_provider_body),
            action = fallback,
            onAction = permissionViewModel::useOwnStore,
        )
        ProviderStatus.NEEDS_PERMISSION -> Gate(
            modifier = modifier,
            icon = Icons.Rounded.Lock,
            title = stringResource(R.string.onboarding_permission_title),
            body = stringResource(R.string.onboarding_permission_body),
            action = stringResource(R.string.onboarding_permission_button),
            onAction = { launcher.launch(permission.permissionsToRequest.toTypedArray()) },
            secondaryAction = fallback,
            onSecondaryAction = permissionViewModel::useOwnStore,
        )
        ProviderStatus.READY -> ReadyGate(
            modifier = modifier,
            navRequest = navRequest,
            onNavRequestConsumed = onNavRequestConsumed,
        )
    }
}

/**
 * Second one-time gate after the provider grant: the first-run flow.
 * [OnboardingViewModel.done] is null until DataStore's first emission — render
 * nothing for that frame rather than flash the wrong screen. A cross-fade eases
 * the hand-off to the app instead of snapping.
 */
@Composable
private fun ReadyGate(
    modifier: Modifier = Modifier,
    navRequest: NavRequest? = null,
    onNavRequestConsumed: () -> Unit = {},
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
) {
    val done by onboardingViewModel.done.collectAsStateWithLifecycle()
    Crossfade(targetState = done, label = "onboardingGate") { state ->
        when (state) {
            // A request waits out onboarding; the host consumes it once it exists.
            // Each destination sizes itself: most keep a readable width, the task
            // list spreads into two panes on a wide window.
            true -> AgendulaNavHost(
                modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                navRequest = navRequest,
                onNavRequestConsumed = onNavRequestConsumed,
            )
            false -> ReadableWidth(modifier) {
                OnboardingFlow(modifier = Modifier.fillMaxSize(), viewModel = onboardingViewModel)
            }
            null -> {}
        }
    }
}

/**
 * A dead end the External store can put the app in: no provider installed, or
 * its permission refused. Built on the same shell as onboarding — it is the
 * first and only screen a user in this state sees, and a bare column reads as a
 * crash rather than a choice.
 *
 * Only reachable by having chosen External in Settings, so the way out —
 * Agendula's own store — is always among the actions, and is the primary one
 * where there is nothing else to try.
 */
@Composable
private fun Gate(
    icon: ImageVector,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryAction: String? = null,
    onSecondaryAction: () -> Unit = {},
) {
    OnboardingScaffold(
        modifier = modifier,
        hero = { SquircleHero(icon) },
        topSpacing = OnboardingSpace.xl,
        actions = {
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(action, style = MaterialTheme.typography.titleMedium)
            }
            if (secondaryAction != null) {
                TextButton(onClick = onSecondaryAction, modifier = Modifier.fillMaxWidth()) {
                    Text(secondaryAction)
                }
            }
        },
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

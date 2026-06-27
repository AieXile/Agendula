package de.jeanlucmakiola.agendula.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.data.tasks.ProviderStatus
import de.jeanlucmakiola.agendula.ui.navigation.AgendulaNavHost
import de.jeanlucmakiola.agendula.ui.permission.PermissionViewModel
import de.jeanlucmakiola.agendula.ui.permission.ReminderOnboardingScreen
import de.jeanlucmakiola.agendula.ui.permission.ReminderOnboardingViewModel

/**
 * App root: gates on the tasks-provider permission, then hands off to
 * [AgendulaNavHost] (lists → task list → detail / edit).
 */
@Composable
fun RootScreen(
    modifier: Modifier = Modifier,
    permissionViewModel: PermissionViewModel = hiltViewModel(),
) {
    val permission by permissionViewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionViewModel.refresh() }

    when (permission.status) {
        ProviderStatus.NO_PROVIDER -> Gate(
            modifier = modifier,
            title = stringResource(R.string.onboarding_no_provider_title),
            body = stringResource(R.string.onboarding_no_provider_body),
        )
        ProviderStatus.NEEDS_PERMISSION -> Gate(
            modifier = modifier,
            title = stringResource(R.string.onboarding_permission_title),
            body = stringResource(R.string.onboarding_permission_body),
            action = stringResource(R.string.onboarding_permission_button),
            onAction = { launcher.launch(permission.permissionsToRequest.toTypedArray()) },
        )
        ProviderStatus.READY -> ReadyGate(modifier = modifier)
    }
}

/**
 * Second one-time gate after the provider grant: the reminder onboarding step.
 * [ReminderOnboardingViewModel.onboardingDone] is null until DataStore's first
 * emission — render nothing for that frame rather than flash the wrong screen.
 * A cross-fade eases the hand-off to the app instead of snapping.
 */
@Composable
private fun ReadyGate(
    modifier: Modifier = Modifier,
    onboardingViewModel: ReminderOnboardingViewModel = hiltViewModel(),
) {
    val done by onboardingViewModel.onboardingDone.collectAsStateWithLifecycle()
    Crossfade(targetState = done, label = "reminderOnboardingGate") { state ->
        when (state) {
            true -> AgendulaNavHost(modifier = modifier)
            false -> ReminderOnboardingScreen(
                onFinished = onboardingViewModel::finish,
                modifier = modifier,
            )
            null -> {}
        }
    }
}

@Composable
private fun Gate(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium)
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}

package de.jeanlucmakiola.agendula.ui.permission

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.jeanlucmakiola.agendula.R

/**
 * One-time onboarding step after the tasks-provider grant: explains that Agendula
 * delivers due reminders itself (tasks providers don't broadcast reminders) and
 * requests `POST_NOTIFICATIONS` (a system dialog on API 33+ only).
 *
 * Reminders default ON: [onFinished] gets true from the primary action even if
 * the system dialog is declined — the OS permission is the real gate, and the
 * Settings toggle re-requests it. "Not now" turns the in-app toggle off.
 */
@Composable
fun ReminderOnboardingScreen(
    onFinished: (remindersEnabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { onFinished(true) }

    OnboardingScaffold(
        modifier = modifier,
        hero = { SquircleHero(Icons.Rounded.Notifications) },
        actions = {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onFinished(true)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.reminder_onboarding_enable_button),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            TextButton(
                onClick = { onFinished(false) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.reminder_onboarding_skip_button))
            }
        },
    ) {
        Text(
            text = stringResource(R.string.app_name).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(OnboardingSpace.xs))
        Text(
            text = stringResource(R.string.reminder_onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.reminder_onboarding_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(OnboardingSpace.xl))

        BenefitRow(
            icon = Icons.Rounded.Notifications,
            title = stringResource(R.string.reminder_benefit_delivery_title),
            body = stringResource(R.string.reminder_benefit_delivery_body),
        )
        Spacer(Modifier.height(OnboardingSpace.sm))
        BenefitRow(
            icon = Icons.Rounded.Schedule,
            title = stringResource(R.string.reminder_benefit_timing_title),
            body = stringResource(R.string.reminder_benefit_timing_body),
        )
        Spacer(Modifier.height(OnboardingSpace.sm))
        BenefitRow(
            icon = Icons.Rounded.Edit,
            title = stringResource(R.string.reminder_benefit_reversible_title),
            body = stringResource(R.string.reminder_benefit_reversible_body),
        )
    }
}

package com.aiexile.agendula.ui.onboarding

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
import androidx.compose.ui.unit.dp
import com.aiexile.agendula.R
import de.jeanlucmakiola.floret.components.BenefitRow
import de.jeanlucmakiola.floret.components.OnboardingSpace

/**
 * Offers due reminders and requests `POST_NOTIFICATIONS` (a system dialog on
 * API 33+ only). Framed as Agendula's own feature: it schedules and delivers
 * them itself, which is also what makes it work over an external store, since
 * no tasks provider broadcasts reminders.
 *
 * Reminders default ON: [onAnswered] gets true from the primary action even if
 * the system dialog is declined — the OS permission is the real gate, and the
 * Settings toggle re-requests it. "Not now" turns the in-app toggle off.
 */
@Composable
internal fun RemindersStep(chrome: StepChrome, onAnswered: (enabled: Boolean) -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { onAnswered(true) }

    StepScaffold(
        chrome = chrome,
        hero = { SquircleHero(Icons.Rounded.Notifications) },
        actions = {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onAnswered(true)
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
                onClick = { onAnswered(false) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.reminder_onboarding_skip_button))
            }
        },
    ) {
        StepHeader(
            title = stringResource(R.string.reminder_onboarding_title),
            body = stringResource(R.string.reminder_onboarding_body),
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

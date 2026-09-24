package de.jeanlucmakiola.agendula.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.floret.components.BenefitRow
import de.jeanlucmakiola.floret.components.OnboardingSpace

/** What the app is, before it asks for anything. */
@Composable
internal fun WelcomeStep(chrome: StepChrome, onContinue: () -> Unit) {
    StepScaffold(
        chrome = chrome,
        hero = { SquircleHero(Icons.Rounded.Checklist) },
        actions = {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_welcome_button),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
    ) {
        StepHeader(
            eyebrow = stringResource(R.string.app_name).uppercase(),
            title = stringResource(R.string.onboarding_welcome_title),
            body = stringResource(R.string.onboarding_welcome_body),
        )

        Spacer(Modifier.height(OnboardingSpace.xl))

        BenefitRow(
            icon = Icons.Rounded.Checklist,
            title = stringResource(R.string.onboarding_welcome_lists_title),
            body = stringResource(R.string.onboarding_welcome_lists_body),
        )
        Spacer(Modifier.height(OnboardingSpace.sm))
        BenefitRow(
            icon = Icons.Rounded.NotificationsActive,
            title = stringResource(R.string.onboarding_welcome_reminders_title),
            body = stringResource(R.string.onboarding_welcome_reminders_body),
        )
        Spacer(Modifier.height(OnboardingSpace.sm))
        BenefitRow(
            icon = Icons.Rounded.CloudSync,
            title = stringResource(R.string.onboarding_welcome_sync_title),
            body = stringResource(R.string.onboarding_welcome_sync_body),
        )
    }
}

package de.jeanlucmakiola.agendula.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.floret.components.BenefitRow
import de.jeanlucmakiola.floret.components.OnboardingSpace

/**
 * The fork: connect a CalDAV account now, or stay on the device. Saying yes
 * hands the next three steps to the add-account wizard; saying no leaves a list
 * to make and a copy to think about.
 */
@Composable
internal fun SyncOfferStep(chrome: StepChrome, onAnswered: (connect: Boolean) -> Unit) {
    StepScaffold(
        chrome = chrome,
        hero = { SquircleHero(Icons.Rounded.CloudSync) },
        actions = {
            Button(
                onClick = { onAnswered(true) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_sync_connect),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            TextButton(
                onClick = { onAnswered(false) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_sync_skip))
            }
        },
    ) {
        StepHeader(
            title = stringResource(R.string.onboarding_sync_title),
            body = stringResource(R.string.onboarding_sync_body),
        )

        Spacer(Modifier.height(OnboardingSpace.xl))

        BenefitRow(
            icon = Icons.Rounded.Devices,
            title = stringResource(R.string.onboarding_sync_devices_title),
            body = stringResource(R.string.onboarding_sync_devices_body),
        )
        Spacer(Modifier.height(OnboardingSpace.sm))
        BenefitRow(
            icon = Icons.Rounded.Backup,
            title = stringResource(R.string.onboarding_sync_copy_title),
            body = stringResource(R.string.onboarding_sync_copy_body),
        )
        Spacer(Modifier.height(OnboardingSpace.sm))
        BenefitRow(
            icon = Icons.Rounded.Lock,
            title = stringResource(R.string.onboarding_sync_yours_title),
            body = stringResource(R.string.onboarding_sync_yours_body),
        )
    }
}

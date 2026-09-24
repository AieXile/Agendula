package de.jeanlucmakiola.agendula.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.R

/**
 * The device-only branch's last word: their lists live here and nowhere else,
 * and this says where the export that fixes that lives. There is nothing to
 * save yet on a first run, so it points the way rather than exporting an empty list.
 */
@Composable
internal fun BackupStep(
    chrome: StepChrome,
    onContinue: () -> Unit,
) {
    StepScaffold(
        chrome = chrome,
        hero = { SquircleHero(Icons.Rounded.SaveAlt) },
        actions = {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_backup_continue),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
    ) {
        StepHeader(
            title = stringResource(R.string.onboarding_backup_title),
            body = stringResource(R.string.onboarding_backup_body_later),
        )
    }
}

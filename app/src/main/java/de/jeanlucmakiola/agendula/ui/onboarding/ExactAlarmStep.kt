package de.jeanlucmakiola.agendula.ui.onboarding

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.ui.common.exactAlarmSettingsIntent

/**
 * Shown after reminders were turned on, when exact alarms are not yet granted.
 * The grant lives on a system page, so this sends the user there and moves on
 * whatever they chose — without it reminders still arrive, just less precisely.
 */
@Composable
internal fun ExactAlarmStep(chrome: StepChrome, onContinue: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { onContinue() }

    StepScaffold(
        chrome = chrome,
        hero = { SquircleHero(Icons.Rounded.Alarm) },
        actions = {
            Button(
                onClick = {
                    val launched = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        runCatching { launcher.launch(context.exactAlarmSettingsIntent()) }.isSuccess
                    if (!launched) onContinue()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_exact_alarms_allow),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.reminder_onboarding_skip_button))
            }
        },
    ) {
        StepHeader(
            title = stringResource(R.string.onboarding_exact_alarms_title),
            body = stringResource(R.string.onboarding_exact_alarms_body),
        )
    }
}

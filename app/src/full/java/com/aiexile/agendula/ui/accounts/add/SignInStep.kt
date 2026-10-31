package com.aiexile.agendula.ui.accounts.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aiexile.agendula.R
import de.jeanlucmakiola.floret.components.Position

/**
 * Step 3a: a username and a password.
 *
 * The generic CalDAV route, taken whenever the server has no browser sign-in to
 * hand off to.
 */
@Composable
internal fun CredentialsStep(
    step: AddAccountStep.EnterCredentials,
    viewModel: AddAccountViewModel,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Two fields in one connected group, which is what the family does with
        // a pair that is filled in together.
        Column {
            FieldRow(
                label = stringResource(R.string.add_account_username_label),
                value = step.username,
                onValueChange = viewModel::onUsernameChanged,
                position = Position.Top,
            )
            FieldRow(
                label = stringResource(R.string.add_account_password_label),
                value = step.password,
                onValueChange = viewModel::onPasswordChanged,
                position = Position.Bottom,
                error = step.error?.text(),
                hint = stringResource(R.string.add_account_password_hint),
                keyboardType = KeyboardType.Password,
                onImeAction = viewModel::onCredentialsSubmitted,
            )
        }
    }
}

/**
 * Step 3b, first half: what is wrong with the page we are about to open.
 *
 * ⚠️ A screen of its own, drawn *before* the Custom Tab rather than behind it.
 * The login URL is where the account password is typed, so a warning about it
 * has to be readable at the moment it still means something — and the primary
 * action stays the way forward, because both causes are legitimate on real
 * deployments and refusing outright would make Login Flow v2 unusable behind an
 * ordinary reverse proxy.
 */
@Composable
internal fun ColumnScope.ConfirmBrowserStep(step: AddAccountStep.ConfirmBrowser) {
    // Title and body come from StepTitle like every other fixed step's do; what
    // is left here is the cause, which is the whole reason the step exists.
    step.hostMismatch?.let { mismatch ->
        QuirkNote(
            text = stringResource(
                R.string.add_account_browser_host_mismatch,
                mismatch.actual,
                mismatch.expected,
            ),
        )
    }

    // Both can be true at once — a proxy that rewrites the host and drops TLS
    // is one misconfiguration, not two — and each is worth its own sentence.
    if (step.insecure) {
        Spacer(Modifier.height(16.dp))
        QuirkNote(text = stringResource(R.string.add_account_browser_insecure))
    }
}

/**
 * Step 3b: the server is signing the user in, in a browser we do not own.
 *
 * The same slot as [CredentialsStep] because only one of the two ever happens —
 * but its own title, mark and body, since "approve this in your browser" and
 * "type your password" have nothing in common but their place in the flow.
 */
@Composable
internal fun ColumnScope.BrowserStep(step: AddAccountStep.WaitingForBrowser) {
    // ⚠️ The title changes too. Swapping only the body left every failure in
    // this phase — a burnt credential, a 429, a maintenance page — sitting
    // under the heading "Waiting for your browser", which reads as "still
    // working" when nothing is working and nothing else will happen.
    Message(
        icon = {
            if (step.error == null) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                Icon(Icons.Rounded.CloudOff, contentDescription = null)
            }
        },
        title = if (step.error == null) {
            stringResource(R.string.add_account_browser_title)
        } else {
            stringResource(R.string.add_account_browser_failed_title)
        },
        text = step.error?.text() ?: stringResource(R.string.add_account_browser_body),
    )

    // ⚠️ A sibling of the message, not a child of its padded column: QuirkNote
    // carries the grouped-list inset itself, and nesting it inside one that
    // already pads by the same amount insets it twice.
    step.hostMismatch?.let { mismatch ->
        Spacer(Modifier.height(16.dp))
        QuirkNote(
            text = stringResource(
                R.string.add_account_browser_host_mismatch,
                mismatch.actual,
                mismatch.expected,
            ),
        )
    }
}

@Composable
internal fun WorkingStep(step: AddAccountStep.Working) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(step.message.text(), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Message(
    icon: @Composable () -> Unit,
    text: String,
    title: String? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

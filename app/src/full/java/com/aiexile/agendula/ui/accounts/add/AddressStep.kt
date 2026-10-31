package com.aiexile.agendula.ui.accounts.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aiexile.agendula.R

/**
 * Step 2: the one address the chosen service actually needs.
 *
 * A hosted service is reached by the email address the user already knows, so it
 * asks for that and nothing else; a server they run is reached by a URL only
 * they have. The old single field had to ask for either, which is why its hint
 * had to show both.
 */
@Composable
internal fun AddressStep(step: AddAccountStep.EnterAddress, viewModel: AddAccountViewModel) {
    val hosted = step.asksForEmail
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FieldRow(
            label = if (hosted) {
                stringResource(R.string.add_account_email_label)
            } else {
                stringResource(R.string.add_account_server_label)
            },
            value = step.input,
            onValueChange = viewModel::onAddressChanged,
            placeholder = step.choice.provider?.primaryDomain?.takeIf { hosted }?.let { "you@$it" }
                ?: stringResource(R.string.add_account_server_hint),
            error = step.error?.let { stringResource(it.message) },
            // An email address is still typed with the URI keyboard: it is the
            // one that carries "@" and "." without a shift, and the field takes
            // a URL too whenever a hosted service is reached by one.
            keyboardType = KeyboardType.Uri,
            onImeAction = viewModel::onAddressSubmitted,
        )
    }
}

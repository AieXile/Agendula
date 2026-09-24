package de.jeanlucmakiola.agendula.ui.accounts.add

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.caldav.ServerQuirk
import de.jeanlucmakiola.floret.components.GroupedListInset
import de.jeanlucmakiola.floret.components.GroupedSurface
import de.jeanlucmakiola.floret.components.InstructionSteps
import de.jeanlucmakiola.floret.components.InlineTextField
import de.jeanlucmakiola.floret.components.Position

/**
 * The family's text input: a tonal grouped surface with a borderless field in
 * it, never Material's outlined box.
 *
 * The label sits above the value rather than floating into a notch, because a
 * `GroupedSurface` has no outline for a notch to interrupt.
 */
@Composable
internal fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    position: Position = Position.Alone,
    placeholder: String = "",
    error: String? = null,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onImeAction: (() -> Unit)? = null,
) {
    // ⚠️ KeyboardType.Password only tells the IME to drop suggestions; it does
    // not mask anything. Without the transformation the app password renders in
    // the clear on screen.
    val masked = keyboardType == KeyboardType.Password
    Column(modifier) {
        GroupedSurface(position = position) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InlineTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = placeholder,
                    keyboardType = keyboardType,
                    // A server address and a password are both case-sensitive,
                    // and sentence-casing either is a support ticket.
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Go,
                    onImeAction = onImeAction,
                    visualTransformation = if (masked) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                )
            }
        }
        // ⚠️ Outside the surface, not inside it. An error rendered within the
        // field's own card reads as part of the value the user typed.
        (error ?: hint)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
    }
}

/** The shell's primary action shape, so every step ends identically. */
@Composable
internal fun PrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * What the chosen service requires, before it is required.
 *
 * ⚠️ Steps, not a paragraph. Fastmail and iCloud both reject the account
 * password with a plain 401, and the fix is a four-action errand in someone
 * else's web app — the exact shape of instruction that gets skimmed and missed
 * when it is written as prose. Google is the odd one out and stays a sentence:
 * there is no procedure, because there is nothing the user can do.
 *
 * ⚠️ Collapsed. By the time this draws, the errand has had a whole screen of its
 * own — [SetupStep] — so what is left to do here is let someone re-read it
 * without going back, not print it a second time under the field they are
 * trying to fill in.
 */
@Composable
internal fun QuirkGuidance(quirk: ServerQuirk) {
    when (quirk) {
        ServerQuirk.FASTMAIL_APP_PASSWORD -> InstructionSteps(
            title = stringResource(R.string.add_account_setup_fastmail_title),
            steps = stringArrayResource(R.array.add_account_setup_fastmail_steps).asList(),
            footnote = stringResource(R.string.add_account_setup_fastmail_footnote),
            collapsible = true,
        )

        ServerQuirk.ICLOUD_APP_SPECIFIC_PASSWORD -> InstructionSteps(
            title = stringResource(R.string.add_account_setup_icloud_title),
            steps = stringArrayResource(R.array.add_account_setup_icloud_steps).asList(),
            footnote = stringResource(R.string.add_account_setup_icloud_footnote),
            collapsible = true,
        )

        ServerQuirk.GOOGLE_UNSUPPORTED ->
            QuirkNote(stringResource(R.string.add_account_quirk_google))

        // A pre-flight warning for the engine, never something to read.
        ServerQuirk.NEXTCLOUD_BRUTE_FORCE_PROTECTED -> Unit
    }
}

/**
 * A single fact the user has to read — one that is not a procedure, so it is a
 * card rather than an [InstructionSteps] list of one.
 *
 * ⚠️ Inset to `GroupedListInset`, like a grouped row. Without it the note ran to
 * the screen edge while every row above it stopped 16dp short, so a caller that
 * is already inside a padded column must not add its own — see [BrowserStep].
 */
@Composable
internal fun QuirkNote(text: String) {
    GroupedSurface(
        position = Position.Alone,
        modifier = Modifier.padding(horizontal = GroupedListInset),
        // Not tertiaryContainer: under dynamic colour that is the low-chroma
        // role, and against a light wallpaper-derived surface the card's own
        // edge disappears even though its text pairing is fine.
        color = MaterialTheme.colorScheme.secondaryContainer,
        gapBelow = false,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(GroupedListInset),
        )
    }
}

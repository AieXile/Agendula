package de.jeanlucmakiola.agendula.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.floret.components.GroupedListInset
import de.jeanlucmakiola.floret.components.GroupedSurface
import de.jeanlucmakiola.floret.components.InlineTextField
import de.jeanlucmakiola.floret.components.Position

/** A lone grouped card holding a name, for the list and filter editors. */
@Composable
internal fun NameField(
    name: String,
    placeholder: String,
    autoFocus: Boolean,
    onNameChange: (String) -> Unit,
    onImeAction: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }
    GroupedSurface(position = Position.Alone, modifier = Modifier.padding(horizontal = GroupedListInset)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            leading?.invoke()
            InlineTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = placeholder,
                imeAction = ImeAction.Done,
                onImeAction = onImeAction,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
    }
}

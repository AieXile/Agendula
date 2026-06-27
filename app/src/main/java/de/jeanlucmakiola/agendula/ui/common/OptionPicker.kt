package de.jeanlucmakiola.agendula.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Shared full-screen scaffold for selection pickers: a full-bleed [Dialog] that
 * reuses [CollapsingScaffold] (collapsing title + back), so a picker is visually
 * identical to a settings sub-page and uses the full width. [content] places the
 * connected grouped rows; selecting one calls [onDismiss]. Ported from Calendula.
 */
@Composable
fun FullScreenPicker(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        CollapsingScaffold(title = title, onBack = onDismiss, content = content)
    }
}

/**
 * General single-select picker, full-screen: each option is a connected grouped
 * row and the current one carries a check. The drop-in for the family's option
 * dialogs (theme, default list, reminder offset, …).
 */
@Composable
fun <T> OptionPicker(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    leading: (@Composable (T) -> Unit)? = null,
) {
    FullScreenPicker(title = title, onDismiss = onDismiss) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            GroupedRow(
                title = label(option),
                position = positionOf(index, options.size),
                selected = isSelected,
                leading = leading?.let { { it(option) } },
                trailing = if (isSelected) {
                    { SelectedCheck() }
                } else {
                    null
                },
                onClick = {
                    onSelect(option)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun SelectedCheck() {
    Icon(
        imageVector = Icons.Rounded.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
    )
}

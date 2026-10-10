package de.jeanlucmakiola.agendula.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.ui.common.DefaultListColor
import de.jeanlucmakiola.agendula.ui.common.ListColorChip
import de.jeanlucmakiola.agendula.ui.common.ListPalette
import de.jeanlucmakiola.agendula.ui.common.NameField
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSurface
import de.jeanlucmakiola.floret.components.OptionPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.pastelize

private const val SWATCHES_PER_ROW = 6

/**
 * Where a list lives.
 *
 * [accountId] null is the device, which is always offered and always first: it
 * needs no server, no permission and no network, so it is the choice that cannot
 * fail. The rest are CalDAV accounts whose home set answered OPTIONS with a
 * creation method — an account that has neither is absent rather than disabled,
 * because "you cannot make a list here" is not a decision the user can act on.
 */
data class ListDestination(val accountId: Long?, val label: String)

/**
 * Create or edit a task list: a name field over the palette of list colours, on
 * the family's full-screen sheet with the commit in its title bar.
 *
 * [initial] null is the create case, and the only one that offers a choice of
 * [destinations] — moving a list between a server and the device is a copy and a
 * delete rather than an edit, and is not this sheet's job.
 *
 * ⚠️ [onDelete] no longer means "device-only list". An account's collection can
 * be deleted now, on the server, which is what the caller wires it to; it stays
 * null for a list the app must not remove — a read-only share, whose removal
 * belongs to whoever owns it.
 */
@Composable
fun ListEditorSheet(
    initial: TaskList?,
    onSave: (name: String, color: Int, accountId: Long?) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    destinations: List<ListDestination> = emptyList(),
    hiddenFromSmartLists: Boolean? = null,
    onHiddenFromSmartListsChange: (Boolean) -> Unit = {},
) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var color by rememberSaveable(initial?.id) { mutableIntStateOf(initial?.color ?: DefaultListColor) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // Saved by id rather than by object: the destination has to survive a
    // rotation, and a Long? is the only part of it that is worth keeping — the
    // labels are rebuilt from the accounts either way.
    var destinationId by rememberSaveable(initial?.id) { mutableStateOf(initial?.accountId) }
    var choosingDestination by rememberSaveable { mutableStateOf(false) }

    // One choice is not a choice. A device with no account that can make
    // collections gets the sheet it has always had.
    val offersChoice = initial == null && destinations.size > 1
    val destination = destinations.firstOrNull { it.accountId == destinationId }
        ?: destinations.firstOrNull()

    val readOnly = initial?.isReadOnly == true
    val valid = name.isNotBlank() && !readOnly
    val commit = {
        if (valid) {
            onSave(name.trim(), color, destinationId)
            onDismiss()
        }
    }

    FullScreenPicker(
        title = stringResource(if (initial == null) R.string.list_new_title else R.string.list_edit_title),
        onDismiss = onDismiss,
        actions = {
            Button(
                onClick = commit,
                enabled = valid,
                modifier = Modifier.padding(end = 12.dp),
            ) { Text(stringResource(R.string.save)) }
        },
    ) {
        ListNameField(
            name = name,
            color = color,
            // A new list opens with the keyboard up: naming it is the whole task.
            autoFocus = initial == null,
            onNameChange = { name = it },
            onImeAction = commit,
        )

        // ⚠️ Said before the work, not after it. A list made on a server and a
        // list made on the device are different things — one syncs to every
        // other client, one never leaves the phone — and finding out which you
        // got by watching it fail to appear elsewhere is the wrong way round.
        if (offersChoice && destination != null) {
            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.list_where))
            GroupedRow(
                title = destination.label,
                position = Position.Alone,
                leading = { Icon(destinationIcon(destination), contentDescription = null) },
                trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                onClick = { choosingDestination = true },
            )
        }

        if (readOnly) {
            Spacer(Modifier.height(20.dp))
            ReadOnlyNote()
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.list_color))
        ListColorGrid(selected = color, onSelect = { color = it })

        // A view preference, not a list edit: it applies at once, read-only share included.
        if (hiddenFromSmartLists != null) {
            Spacer(Modifier.height(24.dp))
            GroupedRow(
                title = stringResource(R.string.list_in_smart_lists),
                summary = stringResource(R.string.list_in_smart_lists_hint),
                position = Position.Alone,
                trailing = {
                    Switch(
                        checked = !hiddenFromSmartLists,
                        onCheckedChange = { onHiddenFromSmartListsChange(!it) },
                    )
                },
                onClick = { onHiddenFromSmartListsChange(!hiddenFromSmartLists) },
            )
        }

        if (onDelete != null) {
            Spacer(Modifier.height(24.dp))
            DeleteRow(onClick = { confirmDelete = true })
        }
        Spacer(Modifier.height(24.dp))
    }

    if (choosingDestination && destination != null) {
        OptionPicker(
            title = stringResource(R.string.list_where),
            options = destinations,
            selected = destination,
            label = { it.label },
            leading = { Icon(destinationIcon(it), contentDescription = null) },
            // No dismissal here: OptionPicker calls onSelect and then onDismiss
            // on the same tap, and closing it twice reads as though it did not.
            onSelect = { destinationId = it.accountId },
            onDismiss = { choosingDestination = false },
        )
    }

    if (confirmDelete && onDelete != null) {
        DeleteListDialog(
            listName = initial?.name.orEmpty(),
            // A synced list is deleted on the server too, and that reaches every
            // other client the account has. Saying so is the difference between
            // a confirmation and a trap.
            synced = initial?.accountId != null,
            onConfirm = {
                confirmDelete = false
                onDelete()
                onDismiss()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** The device and a server are different places, so they get different marks. */
private fun destinationIcon(destination: ListDestination) =
    if (destination.accountId == null) Icons.Rounded.PhoneAndroid else Icons.Rounded.CloudSync

/** A share we may read and not write; the edit has nowhere to land. */
@Composable
private fun ReadOnlyNote() {
    GroupedSurface(
        position = Position.Alone,
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null)
            Text(
                text = stringResource(R.string.list_read_only),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The name, with the chosen colour beside it so the two read as one thing. */
@Composable
internal fun ListNameField(
    name: String,
    color: Int,
    autoFocus: Boolean,
    onNameChange: (String) -> Unit,
    onImeAction: () -> Unit,
) = NameField(
    name = name,
    placeholder = stringResource(R.string.list_name_hint),
    autoFocus = autoFocus,
    onNameChange = onNameChange,
    onImeAction = onImeAction,
    leading = { ListColorChip(color) },
)

/**
 * The palette as two rows of round swatches; the chosen one carries a check.
 * Shared with the onboarding step that makes the user's first list.
 */
@Composable
internal fun ListColorGrid(selected: Int, onSelect: (Int) -> Unit) {
    val dark = isSystemInDarkTheme()
    GroupedSurface(position = Position.Alone, modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ListPalette.chunked(SWATCHES_PER_ROW).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { swatch ->
                        Swatch(
                            color = swatch,
                            dark = dark,
                            selected = swatch == selected,
                            onClick = { onSelect(swatch) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keeps a short final row's swatches at the same size as a full
                    // one's rather than stretching them across the width.
                    repeat(SWATCHES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Swatch(
    color: Int,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill = pastelize(color, dark)
    val label = stringResource(colorLabel(color))
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(fill)
            // selectable, not clickable: the swatch carries its chosen state in
            // semantics, so the check below is decoration rather than the only cue.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = if (fill.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun DeleteRow(onClick: () -> Unit) {
    GroupedSurface(
        position = Position.Alone,
        modifier = Modifier.padding(horizontal = 16.dp),
        onClick = onClick,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.list_delete),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun DeleteListDialog(
    listName: String,
    synced: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_delete_confirm_title)) },
        text = {
            Text(
                stringResource(
                    if (synced) {
                        R.string.list_delete_confirm_message_synced
                    } else {
                        R.string.list_delete_confirm_message
                    },
                    listName,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 8.dp),
    )
}

/** Names the palette entries for screen readers; anything else is just "colour". */
private fun colorLabel(color: Int): Int = when (ListPalette.indexOf(color)) {
    0 -> R.string.list_color_mauve
    1 -> R.string.list_color_red
    2 -> R.string.list_color_orange
    3 -> R.string.list_color_amber
    4 -> R.string.list_color_olive
    5 -> R.string.list_color_green
    6 -> R.string.list_color_teal
    7 -> R.string.list_color_cyan
    8 -> R.string.list_color_blue
    9 -> R.string.list_color_indigo
    10 -> R.string.list_color_purple
    11 -> R.string.list_color_pink
    else -> R.string.list_color
}

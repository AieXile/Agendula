package de.jeanlucmakiola.agendula.ui.common

import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.positionOf
import de.jeanlucmakiola.floret.components.InlineTextField
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.reminders.ReminderOverride
import de.jeanlucmakiola.floret.reminders.ReminderUnit
import de.jeanlucmakiola.floret.reminders.decomposeReminderMinutes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.R

/**
 * Reminder lead-time picker, full-screen: the grouped list of preset lead times
 * plus a "Custom" row that expands an inline amount field + unit selector.
 * [allowInherit] adds a "Use default" row (per-list overrides); [allowNone] adds
 * a "None" row. Returns the choice as a [ReminderOverride]. Ported from
 * Calendula's ReminderDefaultPicker.
 */
@Composable
fun ReminderLeadPicker(
    title: String,
    selected: ReminderOverride,
    allowInherit: Boolean,
    allowNone: Boolean,
    onSelect: (ReminderOverride) -> Unit,
    onDismiss: () -> Unit,
    presets: List<Int> = REMINDER_PRESETS,
) {
    val selectedMinutes = (selected as? ReminderOverride.Minutes)?.minutes
    val customSelected = selectedMinutes != null && selectedMinutes !in presets
    val seed = decomposeReminderMinutes(selectedMinutes?.takeIf { customSelected })

    var customExpanded by rememberSaveable { mutableStateOf(false) }
    var amountText by rememberSaveable { mutableStateOf(seed.amount?.toString() ?: "") }
    var unit by rememberSaveable { mutableStateOf(seed.unit) }

    val options = buildList {
        if (allowInherit) add(ReminderOverride.Inherit)
        if (allowNone) add(ReminderOverride.None)
        presets.forEach { add(ReminderOverride.Minutes(it)) }
    }
    val rowCount = options.size + 1 // + the custom row

    FullScreenPicker(title = title, onDismiss = onDismiss) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            GroupedRow(
                title = reminderOverrideLabel(option),
                position = positionOf(index, rowCount),
                selected = isSelected,
                onClick = {
                    onSelect(option)
                    onDismiss()
                },
            )
        }
        // Expanded, the Custom row connects downward into the editor card so the
        // two read as one grouped container.
        GroupedRow(
            title = if (customSelected) {
                stringResource(R.string.reminder_custom_with_value, reminderLeadTimeLabel(selectedMinutes!!))
            } else {
                stringResource(R.string.reminder_custom)
            },
            position = if (customExpanded) Position.Top else positionOf(options.size, rowCount),
            selected = customSelected,
            onClick = { customExpanded = !customExpanded },
        )
        AnimatedVisibility(
            visible = customExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            CustomReminderEditor(
                amountText = amountText,
                onAmountChange = { amountText = it },
                unit = unit,
                onUnitChange = { unit = it },
                onConfirm = { minutes ->
                    onSelect(ReminderOverride.Minutes(minutes))
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun CustomReminderEditor(
    amountText: String,
    onAmountChange: (String) -> Unit,
    unit: ReminderUnit,
    onUnitChange: (ReminderUnit) -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val amount = amountText.toIntOrNull()?.takeIf { it in 1..999 }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // A Position.Bottom shape: tight top corners meeting the row, full bottom.
        shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 22.dp, bottomEnd = 22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ReminderUnit.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = unit == entry,
                        onClick = { onUnitChange(entry) },
                        shape = SegmentedButtonDefaults.itemShape(index, ReminderUnit.entries.size),
                        label = { Text(stringResource(reminderUnitLabel(entry))) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    InlineTextField(
                        value = amountText,
                        onValueChange = { text -> if (text.length <= 3 && text.all(Char::isDigit)) onAmountChange(text) },
                        placeholder = "10",
                        textStyle = MaterialTheme.typography.titleMedium,
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.width(72.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = amount?.let { reminderLeadTimeLabel(it * unit.minutesFactor) }
                        ?: stringResource(R.string.reminder_custom_amount),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                FilledTonalButton(
                    onClick = { amount?.let { onConfirm(it * unit.minutesFactor) } },
                    enabled = amount != null,
                ) {
                    Text(stringResource(R.string.reminder_custom_set))
                }
            }
        }
    }
}

@Composable
private fun reminderOverrideLabel(override: ReminderOverride): String = when (override) {
    ReminderOverride.Inherit -> stringResource(R.string.reminder_use_default)
    ReminderOverride.None -> stringResource(R.string.reminder_none)
    is ReminderOverride.Minutes -> reminderLeadTimeLabel(override.minutes)
}

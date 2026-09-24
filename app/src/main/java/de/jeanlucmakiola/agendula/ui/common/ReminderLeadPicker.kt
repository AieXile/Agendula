package de.jeanlucmakiola.agendula.ui.common

import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.positionOf
import de.jeanlucmakiola.floret.components.DialogAmountField
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.reminders.reminderOverrideForMinutes
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import de.jeanlucmakiola.floret.reminders.ReminderOverride
import de.jeanlucmakiola.floret.reminders.ReminderUnit
import de.jeanlucmakiola.floret.reminders.decomposeReminderMinutes
import androidx.compose.animation.AnimatedVisibility
import de.jeanlucmakiola.floret.identity.collapseExit
import de.jeanlucmakiola.floret.identity.expandEnter
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
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.R

/**
 * Reminder lead-time picker, full-screen: the grouped list of preset lead times
 * plus a "Custom" row that expands an inline amount field + unit selector.
 * [allowInherit] adds a "Use default" row (per-list overrides); [allowNone] adds
 * a "None" row; [allDay] switches to day-scale presets and wording. Returns the
 * choice as a [ReminderOverride]. Ported from Calendula's ReminderDefaultPicker.
 */
@Composable
fun ReminderLeadPicker(
    title: String,
    selected: ReminderOverride,
    allowInherit: Boolean,
    allowNone: Boolean,
    onSelect: (ReminderOverride) -> Unit,
    onDismiss: () -> Unit,
    allDay: Boolean = false,
    presets: List<Int> = if (allDay) ALLDAY_REMINDER_PRESETS else REMINDER_PRESETS,
) {
    // Agendula is single-reminder: an override carries a one-element list, so
    // take the single value for this single-select picker.
    val selectedMinutes = (selected as? ReminderOverride.Minutes)?.minutes?.firstOrNull()
    val customSelected = selectedMinutes != null && selectedMinutes !in presets
    val seed = decomposeReminderMinutes(selectedMinutes?.takeIf { customSelected })

    var customExpanded by rememberSaveable { mutableStateOf(false) }
    var amountText by rememberSaveable { mutableStateOf(seed.amount?.toString() ?: "") }
    var unit by rememberSaveable { mutableStateOf(seed.unit) }

    val options = buildList {
        if (allowInherit) add(ReminderOverride.Inherit)
        if (allowNone) add(ReminderOverride.None)
        presets.forEach { add(ReminderOverride.Minutes(listOf(it))) }
    }
    val rowCount = options.size + 1 // + the custom row

    FullScreenPicker(title = title, onDismiss = onDismiss) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            GroupedRow(
                title = reminderOverrideLabel(option, allDay),
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
                stringResource(R.string.reminder_custom_with_value, reminderLeadTimeLabel(selectedMinutes!!, allDay))
            } else {
                stringResource(R.string.reminder_custom)
            },
            position = if (customExpanded) Position.Top else positionOf(options.size, rowCount),
            selected = customSelected,
            onClick = { customExpanded = !customExpanded },
        )
        AnimatedVisibility(
            visible = customExpanded,
            enter = expandEnter(),
            exit = collapseExit(),
        ) {
            CustomReminderEditor(
                amountText = amountText,
                onAmountChange = { amountText = it },
                unit = unit,
                onUnitChange = { unit = it },
                onConfirm = { minutes ->
                    onSelect(ReminderOverride.Minutes(listOf(minutes)))
                    onDismiss()
                },
            )
        }
    }
}

/**
 * Default-reminder picker, full-screen and **multi-select**: each preset lead
 * time (plus any custom one chosen) toggles on its own, so a default can carry
 * several reminders — 30 minutes before *and* at due time. "No reminder" and, for
 * a per-list picker ([allowInherit]), "Use default" sit above as exclusive rows.
 * Clearing the last time falls back to "Use default" on a per-list picker and to
 * "No reminder" on the global one. Changes apply live; the user leaves via back.
 * [allDay] switches to day-scale presets, and [leadTimeSummary] adds a second
 * line to each lead-time row. Ported from Calendula's ReminderDefaultPicker.
 */
@Composable
fun ReminderDefaultPicker(
    title: String,
    selected: ReminderOverride,
    allowInherit: Boolean,
    onSelect: (ReminderOverride) -> Unit,
    onDismiss: () -> Unit,
    allDay: Boolean = false,
    presets: List<Int> = if (allDay) ALLDAY_REMINDER_PRESETS else REMINDER_PRESETS,
    leadTimeSummary: String? = null,
) {
    // Local state is authoritative once edited, so quick toggles compose on each
    // other instead of racing the round-trip through the settings flow.
    var current by remember { mutableStateOf(selected) }
    var userEdited by remember { mutableStateOf(false) }
    LaunchedEffect(selected) { if (!userEdited) current = selected }
    val inherits = current is ReminderOverride.Inherit
    val isNone = current is ReminderOverride.None
    val selectedMinutes = (current as? ReminderOverride.Minutes)?.minutes.orEmpty()
    // Custom values seen this session keep their row once unchecked.
    val seenCustom = remember { mutableSetOf<Int>() }
    seenCustom += selectedMinutes.filter { it !in presets }
    val rows = presets + seenCustom.sorted()

    var customExpanded by rememberSaveable { mutableStateOf(false) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(ReminderUnit.Minutes) }

    fun apply(override: ReminderOverride) {
        userEdited = true
        current = override
        onSelect(override)
    }
    fun emit(minutes: List<Int>) = apply(reminderOverrideForMinutes(minutes, allowInherit))
    fun toggle(minute: Int) =
        emit(if (minute in selectedMinutes) selectedMinutes - minute else selectedMinutes + minute)

    FullScreenPicker(title = title, onDismiss = onDismiss) {
        if (allowInherit) {
            GroupedRow(
                title = stringResource(R.string.reminder_use_default),
                position = Position.Top,
                selected = inherits,
                trailing = if (inherits) ({ SelectedCheck() }) else null,
                onClick = { apply(ReminderOverride.Inherit) },
            )
        }
        GroupedRow(
            title = stringResource(R.string.reminder_none),
            position = if (allowInherit) Position.Bottom else Position.Alone,
            selected = isNone,
            trailing = if (isNone) ({ SelectedCheck() }) else null,
            onClick = { apply(ReminderOverride.None) },
        )
        Spacer(Modifier.height(24.dp))
        val rowCount = rows.size + 1 // + the custom row
        rows.forEachIndexed { index, minute ->
            val checked = minute in selectedMinutes
            GroupedRow(
                title = reminderLeadTimeLabel(minute, allDay),
                summary = leadTimeSummary,
                position = positionOf(index, rowCount),
                selected = checked,
                trailing = if (checked) ({ SelectedCheck() }) else null,
                onClick = { toggle(minute) },
            )
        }
        GroupedRow(
            title = stringResource(R.string.reminder_custom),
            position = if (customExpanded) Position.Top else positionOf(rows.size, rowCount),
            onClick = { customExpanded = !customExpanded },
        )
        AnimatedVisibility(
            visible = customExpanded,
            enter = expandEnter(),
            exit = collapseExit(),
        ) {
            CustomReminderEditor(
                amountText = amountText,
                onAmountChange = { amountText = it },
                unit = unit,
                onUnitChange = { unit = it },
                onConfirm = { minutes ->
                    emit(selectedMinutes + minutes)
                    amountText = ""
                    customExpanded = false
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
                DialogAmountField(value = amountText, onValueChange = onAmountChange, placeholder = "10")
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
private fun reminderOverrideLabel(override: ReminderOverride, allDay: Boolean): String = when (override) {
    ReminderOverride.Inherit -> stringResource(R.string.reminder_use_default)
    ReminderOverride.None -> stringResource(R.string.reminder_none)
    is ReminderOverride.Minutes -> reminderLeadTimeLabel(override.minutes.first(), allDay)
}

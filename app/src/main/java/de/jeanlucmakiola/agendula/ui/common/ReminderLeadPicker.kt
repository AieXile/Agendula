package de.jeanlucmakiola.agendula.ui.common

import de.jeanlucmakiola.floret.components.CustomAmountEditor
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.positionOf
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.reminders.ReminderLead
import de.jeanlucmakiola.floret.reminders.reminderOverrideForLeads
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import de.jeanlucmakiola.floret.reminders.ReminderOverride
import de.jeanlucmakiola.floret.reminders.ReminderUnit
import de.jeanlucmakiola.floret.reminders.decomposeReminderMinutes
import androidx.compose.animation.AnimatedVisibility
import de.jeanlucmakiola.floret.identity.collapseExit
import de.jeanlucmakiola.floret.identity.expandEnter
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.R

/**
 * Reminder lead-time picker, full-screen: the grouped list of preset lead times
 * plus a "Custom" row that expands an inline amount field + unit selector.
 * [allowInherit] adds a "Use default" row (per-list overrides); [allowNone] adds
 * a "None" row; [allDay] switches to day-scale presets and wording, and its
 * custom amounts to days and weeks. [header] sits above the rows, for whatever
 * else the caller's reminder carries. Returns the choice as a
 * [ReminderOverride]. Ported from Calendula's ReminderDefaultPicker.
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
    header: (@Composable ColumnScope.() -> Unit)? = null,
) {
    // Agendula is single-reminder: an override carries a one-element list, so
    // take the single value for this single-select picker.
    val selectedMinutes = (selected as? ReminderOverride.Minutes)?.minutes?.firstOrNull()
    val customSelected = selectedMinutes != null && selectedMinutes !in presets
    val seed = decomposeReminderMinutes(selectedMinutes?.takeIf { customSelected })

    var customExpanded by rememberSaveable { mutableStateOf(false) }
    var amountText by rememberSaveable { mutableStateOf(seed.amount?.toString() ?: "") }
    var unit by rememberSaveable { mutableStateOf(seed.unit.takeIf { it in reminderUnits(allDay) } ?: reminderUnits(allDay).first()) }

    val options = buildList {
        if (allowInherit) add(ReminderOverride.Inherit)
        if (allowNone) add(ReminderOverride.None)
        presets.forEach { add(ReminderOverride.Minutes(listOf(it))) }
    }
    val rowCount = options.size + 1 // + the custom row

    FullScreenPicker(title = title, onDismiss = onDismiss) {
        header?.invoke(this)
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
                units = reminderUnits(allDay),
                allDay = allDay,
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
 * line to each lead-time row. A start / due toggle picks which date the rows
 * count from, and the choice carries it ([ReminderOverride.Minutes.fromStart]).
 * Ported from Calendula's ReminderDefaultPicker.
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
    val selectedLeads = (current as? ReminderOverride.Minutes)?.leads.orEmpty()
    var fromStart by rememberSaveable { mutableStateOf(true) }
    val selectedMinutes = selectedLeads.filter { it.fromStart == fromStart }.map { it.minutes }
    // Custom values seen this session keep their row once unchecked.
    val seenCustom = remember { mutableSetOf<ReminderLead>() }
    seenCustom += selectedLeads.filter { it.minutes !in presets }
    val rows = presets + seenCustom.filter { it.fromStart == fromStart }.map { it.minutes }.sorted()

    var customExpanded by rememberSaveable { mutableStateOf(false) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(reminderUnits(allDay).first()) }

    fun apply(override: ReminderOverride) {
        userEdited = true
        current = override
        onSelect(override)
    }
    fun emit(leads: List<ReminderLead>) = apply(reminderOverrideForLeads(leads, allowInherit))
    fun lead(minute: Int) = ReminderLead(minute, fromStart)
    fun toggle(minute: Int) =
        emit(if (minute in selectedMinutes) selectedLeads - lead(minute) else selectedLeads + lead(minute))

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
        ReminderAnchorToggle(fromStart = fromStart, onChange = { fromStart = it })
        Spacer(Modifier.height(16.dp))
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
                units = reminderUnits(allDay),
                allDay = allDay,
                onConfirm = { minutes ->
                    emit(selectedLeads + lead(minutes))
                    amountText = ""
                    customExpanded = false
                },
            )
        }
    }
}

/** Which date a reminder counts back from. */
@Composable
fun ReminderAnchorToggle(fromStart: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        listOf(true, false).forEachIndexed { index, start ->
            SegmentedButton(
                selected = fromStart == start,
                onClick = { onChange(start) },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                label = { Text(stringResource(reminderAnchorLabel(start))) },
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
    units: List<ReminderUnit>,
    allDay: Boolean,
    onConfirm: (Int) -> Unit,
) {
    val amount = amountText.toIntOrNull()?.takeIf { it in 1..999 }
    CustomAmountEditor(
        amountText = amountText,
        onAmountChange = onAmountChange,
        preview = amount?.let { reminderLeadTimeLabel(it * unit.minutesFactor, allDay) }
            ?: stringResource(R.string.reminder_custom_amount),
        setLabel = stringResource(R.string.reminder_custom_set),
        confirmEnabled = amount != null,
        onConfirm = { amount?.let { onConfirm(it * unit.minutesFactor) } },
        unitLabels = units.map { stringResource(reminderUnitLabel(it)) },
        selectedUnit = units.indexOf(unit),
        onUnitChange = { onUnitChange(units[it]) },
    )
}

/**
 * The units a custom lead is entered in. An all-day reminder's time of day is
 * set on its own, so its lead is whole days: an hour count would read back as a
 * time of day.
 */
private fun reminderUnits(allDay: Boolean): List<ReminderUnit> =
    if (allDay) listOf(ReminderUnit.Days, ReminderUnit.Weeks) else ReminderUnit.entries

@Composable
private fun reminderOverrideLabel(override: ReminderOverride, allDay: Boolean): String = when (override) {
    ReminderOverride.Inherit -> stringResource(R.string.reminder_use_default)
    ReminderOverride.None -> stringResource(R.string.reminder_none)
    is ReminderOverride.Minutes -> reminderLeadTimeLabel(override.minutes.first(), allDay)
}

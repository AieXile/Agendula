package de.jeanlucmakiola.agendula.ui.edit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.domain.recurrence.RecurrenceEnd
import de.jeanlucmakiola.agendula.domain.recurrence.RecurrenceFreq
import de.jeanlucmakiola.agendula.domain.recurrence.SimpleRecurrence
import de.jeanlucmakiola.agendula.domain.recurrence.parseSimpleRecurrence
import de.jeanlucmakiola.agendula.domain.recurrence.toRRule
import de.jeanlucmakiola.agendula.ui.common.nextOccurrencesText
import de.jeanlucmakiola.agendula.ui.common.recurrenceText
import de.jeanlucmakiola.floret.components.DialogAmountField
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSurface
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.components.positionOf
import de.jeanlucmakiola.floret.identity.collapseExit
import de.jeanlucmakiola.floret.identity.expandEnter
import de.jeanlucmakiola.floret.locale.currentLocale
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toJavaDayOfWeek
import kotlinx.datetime.toJavaLocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

private enum class RecurrenceEndMode { Never, Until, Count }

/**
 * Full-screen recurrence picker in two steps: the plain frequencies as a
 * tappable list (one tap applies and closes), and a "Custom" step with an
 * interval, weekday toggles (weekly only) and an end condition, confirmed with
 * OK. A rule the simple shape can't express stays untouched unless the user
 * picks something here.
 *
 * [current] is the bare RRULE value (or null for none); [startDate] anchors the
 * "Next: …" previews — for a task, its start date or else its due date.
 * [onSelect] gets the new RRULE value, or null for "Does not repeat".
 */
@Composable
fun RecurrencePicker(
    current: String?,
    startDate: LocalDate,
    firstDayOfWeek: DayOfWeek,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val startDay = startDate.dayOfWeek
    val parsed = remember(current) { current?.let { parseSimpleRecurrence(it) } }
    val isPlainPreset = parsed != null && parsed.interval == 1 &&
        parsed.end == RecurrenceEnd.Never && parsed.byDays.isEmpty()
    var customMode by rememberSaveable { mutableStateOf(false) }
    var intervalText by rememberSaveable { mutableStateOf((parsed?.interval ?: 1).toString()) }
    var freq by rememberSaveable { mutableStateOf(parsed?.freq ?: RecurrenceFreq.Weekly) }
    // The start weekday stands in until the user picks days herself.
    var daysMask by rememberSaveable {
        mutableStateOf((parsed?.byDays?.takeIf { it.isNotEmpty() } ?: setOf(startDay)).toMask())
    }
    var endMode by rememberSaveable {
        mutableStateOf(
            when (parsed?.end) {
                is RecurrenceEnd.Until -> RecurrenceEndMode.Until
                is RecurrenceEnd.Count -> RecurrenceEndMode.Count
                else -> RecurrenceEndMode.Never
            },
        )
    }
    var untilIso by rememberSaveable {
        mutableStateOf((parsed?.end as? RecurrenceEnd.Until)?.date?.toString())
    }
    var countText by rememberSaveable {
        mutableStateOf(((parsed?.end as? RecurrenceEnd.Count)?.times ?: 10).toString())
    }
    var showUntilPicker by rememberSaveable { mutableStateOf(false) }

    val locale = currentLocale()
    val untilDate = untilIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val untilSummary = remember(untilDate, locale) {
        untilDate?.let {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale).format(it.toJavaLocalDate())
        }
    }
    // A blank amount reads as its placeholder; only a real out-of-range value is invalid.
    val interval = if (intervalText.isBlank()) 1 else intervalText.toIntOrNull()?.takeIf { it in 1..999 }
    val count = if (countText.isBlank()) 10 else countText.toIntOrNull()?.takeIf { it in 1..999 }
    val customEnd: RecurrenceEnd? = when (endMode) {
        RecurrenceEndMode.Never -> RecurrenceEnd.Never
        RecurrenceEndMode.Until -> untilDate?.let { RecurrenceEnd.Until(it) }
        RecurrenceEndMode.Count -> count?.let { RecurrenceEnd.Count(it) }
    }
    val customRule: SimpleRecurrence? = if (interval != null && customEnd != null) {
        SimpleRecurrence(
            freq = freq,
            interval = interval,
            end = customEnd,
            byDays = if (freq == RecurrenceFreq.Weekly) daysMask.toDaySet() else emptySet(),
        )
    } else {
        null
    }
    val customResult: String? = customRule?.toRRule()

    FullScreenPicker(
        title = stringResource(R.string.recurrence_title),
        onDismiss = onDismiss,
        actions = {
            if (customMode) {
                TextButton(
                    enabled = customResult != null,
                    onClick = { customResult?.let(onSelect) },
                ) { Text(stringResource(android.R.string.ok)) }
            }
        },
    ) {
        if (!customMode) {
            PresetList(
                current = current,
                parsed = parsed,
                isPlainPreset = isPlainPreset,
                startDate = startDate,
                locale = locale,
                onSelect = onSelect,
                onCustom = { customMode = true },
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Live read-out of exactly what OK would save; minLines keeps the
                // controls below from shifting as the phrase grows.
                Text(
                    text = customResult?.let { recurrenceText(it, locale) }
                        ?: AnnotatedString(stringResource(R.string.recurrence_incomplete)),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (customResult != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Text(
                    text = customRule?.let { nextOccurrencesText(it, startDate, locale) }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )

                GroupedSurface(
                    position = Position.Alone,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .padding(horizontal = 16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.recurrence_every),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.width(12.dp))
                            DialogAmountField(
                                value = intervalText,
                                onValueChange = { intervalText = it },
                                placeholder = "1",
                            )
                        }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            RecurrenceFreq.entries.forEachIndexed { index, entry ->
                                SegmentedButton(
                                    selected = freq == entry,
                                    onClick = { freq = entry },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index, RecurrenceFreq.entries.size,
                                    ),
                                    // No check slot: four segments share one row and
                                    // its width would ellipsize the longer labels.
                                    icon = {},
                                    label = {
                                        Text(
                                            text = stringResource(recurrenceUnitLabel(entry)),
                                            maxLines = 1,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = freq == RecurrenceFreq.Weekly,
                    enter = expandEnter(),
                    exit = collapseExit(),
                ) {
                    GroupedSurface(
                        position = Position.Alone,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .padding(horizontal = 16.dp),
                    ) {
                        WeekdayToggleRow(
                            selected = daysMask.toDaySet(),
                            onToggle = { day -> daysMask = daysMask xor day.toMaskBit() },
                            locale = locale,
                            firstDay = firstDayOfWeek,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = stringResource(R.string.recurrence_ends),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                    )
                    CheckRow(
                        title = stringResource(R.string.recurrence_end_never),
                        position = Position.Top,
                        selected = endMode == RecurrenceEndMode.Never,
                        onClick = { endMode = RecurrenceEndMode.Never },
                    )
                    CheckRow(
                        title = stringResource(R.string.recurrence_end_until),
                        summary = untilSummary,
                        position = Position.Middle,
                        selected = endMode == RecurrenceEndMode.Until,
                        onClick = {
                            endMode = RecurrenceEndMode.Until
                            showUntilPicker = true
                        },
                    )
                    CheckRow(
                        title = stringResource(R.string.recurrence_end_count),
                        position = if (endMode == RecurrenceEndMode.Count) Position.Middle else Position.Bottom,
                        selected = endMode == RecurrenceEndMode.Count,
                        onClick = { endMode = RecurrenceEndMode.Count },
                    )
                    AnimatedVisibility(
                        visible = endMode == RecurrenceEndMode.Count,
                        enter = expandEnter(),
                        exit = collapseExit(),
                    ) {
                        GroupedSurface(
                            position = Position.Bottom,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(16.dp),
                            ) {
                                DialogAmountField(
                                    value = countText,
                                    onValueChange = { countText = it },
                                    placeholder = "10",
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.recurrence_times),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showUntilPicker) {
        UntilDatePicker(
            initial = untilDate ?: startDate,
            onConfirm = {
                untilIso = it.toString()
                showUntilPicker = false
            },
            onDismiss = {
                showUntilPicker = false
                // A dateless "on a date" can't be saved; fall back to "never".
                if (untilIso == null) endMode = RecurrenceEndMode.Never
            },
        )
    }
}

@Composable
private fun PresetList(
    current: String?,
    parsed: SimpleRecurrence?,
    isPlainPreset: Boolean,
    startDate: LocalDate,
    locale: Locale,
    onSelect: (String?) -> Unit,
    onCustom: () -> Unit,
) {
    val rowCount = RecurrenceFreq.entries.size + 2
    CheckRow(
        title = stringResource(R.string.recurrence_none),
        position = positionOf(0, rowCount),
        selected = current == null,
        onClick = { onSelect(null) },
    )
    RecurrenceFreq.entries.forEachIndexed { index, entry ->
        CheckRow(
            title = stringResource(recurrencePresetLabel(entry)),
            summary = nextOccurrencesText(SimpleRecurrence(entry), startDate, locale),
            position = positionOf(index + 1, rowCount),
            selected = isPlainPreset && parsed?.freq == entry,
            onClick = { onSelect(SimpleRecurrence(entry).toRRule()) },
        )
    }
    CheckRow(
        title = stringResource(R.string.recurrence_custom),
        position = positionOf(rowCount - 1, rowCount),
        selected = current != null && !isPlainPreset,
        onClick = onCustom,
    )
}

@Composable
private fun CheckRow(
    title: String,
    position: Position,
    selected: Boolean,
    onClick: () -> Unit,
    summary: String? = null,
) {
    GroupedRow(
        title = title,
        summary = summary,
        position = position,
        selected = selected,
        trailing = if (selected) {
            { SelectedCheck() }
        } else {
            null
        },
        onClick = onClick,
    )
}

/** M3 date picker for the UNTIL day; the picker speaks UTC-midnight millis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UntilDatePicker(
    initial: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toEpochDays() * MILLIS_PER_DAY,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onConfirm(LocalDate.fromEpochDays((millis / MILLIS_PER_DAY).toInt()))
                    } ?: onDismiss()
                },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

private const val MILLIS_PER_DAY: Long = 86_400_000L

/** One tappable circle per weekday in locale week order, multi-select (BYDAY picks). */
@Composable
private fun WeekdayToggleRow(
    selected: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit,
    locale: Locale,
    firstDay: DayOfWeek,
    modifier: Modifier = Modifier,
) {
    val days = remember(firstDay) {
        (0 until 7).map { DayOfWeek(((firstDay.isoDayNumber - 1 + it) % 7) + 1) }
    }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth(),
    ) {
        days.forEach { day ->
            val isSelected = day in selected
            Surface(
                onClick = { onToggle(day) },
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = day.toJavaDayOfWeek().getDisplayName(JavaTextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** Weekday sets travel through rememberSaveable as an ISO-day bitmask. */
private fun DayOfWeek.toMaskBit(): Int = 1 shl (isoDayNumber - 1)

private fun Set<DayOfWeek>.toMask(): Int = fold(0) { acc, day -> acc or day.toMaskBit() }

private fun Int.toDaySet(): Set<DayOfWeek> =
    DayOfWeek.entries.filter { this and it.toMaskBit() != 0 }.toSet()

private fun recurrencePresetLabel(freq: RecurrenceFreq): Int = when (freq) {
    RecurrenceFreq.Daily -> R.string.recurrence_daily
    RecurrenceFreq.Weekly -> R.string.recurrence_weekly
    RecurrenceFreq.Monthly -> R.string.recurrence_monthly
    RecurrenceFreq.Yearly -> R.string.recurrence_yearly
}

private fun recurrenceUnitLabel(freq: RecurrenceFreq): Int = when (freq) {
    RecurrenceFreq.Daily -> R.string.recurrence_unit_days
    RecurrenceFreq.Weekly -> R.string.recurrence_unit_weeks
    RecurrenceFreq.Monthly -> R.string.recurrence_unit_months
    RecurrenceFreq.Yearly -> R.string.recurrence_unit_years
}

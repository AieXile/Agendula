package de.jeanlucmakiola.floret.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import de.jeanlucmakiola.floret.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Instant

private val zone: ZoneId get() = ZoneId.systemDefault()

private fun Instant.toLocalDate(): LocalDate =
    java.time.Instant.ofEpochMilli(toEpochMilliseconds()).atZone(zone).toLocalDate()

private fun Instant.toLocalTime(): LocalTime =
    java.time.Instant.ofEpochMilli(toEpochMilliseconds()).atZone(zone).toLocalTime()

private fun localToInstant(date: LocalDate, time: LocalTime): Instant =
    Instant.fromEpochMilliseconds(date.atTime(time).atZone(zone).toInstant().toEpochMilli())

/**
 * A labelled date(-time) field for the edit form: a tonal row showing the
 * current value (or nothing), tappable to pick a date and — unless [allDay] —
 * a time. A clear affordance appears once a value is set. Emits `null` when
 * cleared. Styled to match the app's rounded tonal family.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeField(
    label: String,
    value: Instant?,
    allDay: Boolean,
    onChange: (Instant?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }

    Surface(
        onClick = { showDatePicker = true },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Event, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value?.formatDateTime(allDay) ?: stringResource(R.string.edit_set),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (value != null) {
                IconButton(onClick = { onChange(null) }) {
                    Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.edit_clear))
                }
            }
        }
    }

    if (showDatePicker) {
        val initialMillis = (value ?: Instant.fromEpochMilliseconds(System.currentTimeMillis()))
            .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        val millis = dateState.selectedDateMillis ?: return@TextButton
                        val date = java.time.Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        if (allDay) {
                            onChange(localToInstant(date, LocalTime.MIDNIGHT))
                        } else {
                            pendingDate = date
                            showTimePicker = true
                        }
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) { DatePicker(state = dateState) }
    }

    if (showTimePicker) {
        val base = value ?: Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val timeState = rememberTimePickerState(
            initialHour = base.toLocalTime().hour,
            initialMinute = base.toLocalTime().minute,
        )
        Dialog(onDismissRequest = { showTimePicker = false }) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TimePicker(state = timeState)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showTimePicker = false }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        TextButton(onClick = {
                            showTimePicker = false
                            val date = pendingDate ?: return@TextButton
                            onChange(localToInstant(date, LocalTime.of(timeState.hour, timeState.minute)))
                        }) { Text(stringResource(android.R.string.ok)) }
                    }
                }
            }
        }
    }
}

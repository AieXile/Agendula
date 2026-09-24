package de.jeanlucmakiola.agendula.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.floret.reminders.ReminderUnit

/** Common reminder lead times offered as quick picks in the reminder pickers. */
val REMINDER_PRESETS = listOf(0, 5, 10, 30, 60, 1_440)

/** Day-scale lead times for all-day tasks: on the day, 1 and 2 days, 1 week before. */
val ALLDAY_REMINDER_PRESETS = listOf(0, 1_440, 2_880, 10_080)

@StringRes
fun reminderUnitLabel(unit: ReminderUnit): Int = when (unit) {
    ReminderUnit.Minutes -> R.string.reminder_unit_minutes
    ReminderUnit.Hours -> R.string.reminder_unit_hours
    ReminderUnit.Days -> R.string.reminder_unit_days
    ReminderUnit.Weeks -> R.string.reminder_unit_weeks
}

/**
 * Humanise a reminder lead time (minutes before due) into one line: "At time of
 * task" (0), "10 minutes before", "1 hour before", … Shared by the reminder
 * pickers and the settings summaries so the wording never drifts. For an
 * [allDay] task, 0 reads "On the day". Ported from Calendula.
 */
@Composable
fun reminderLeadTimeLabel(minutes: Int, allDay: Boolean = false): String = when {
    minutes <= 0 && allDay -> stringResource(R.string.reminder_on_the_day)
    minutes <= 0 -> stringResource(R.string.reminder_at_due)
    minutes % 10_080 == 0 ->
        pluralStringResource(R.plurals.reminder_weeks, minutes / 10_080, minutes / 10_080)
    minutes % 1_440 == 0 ->
        pluralStringResource(R.plurals.reminder_days, minutes / 1_440, minutes / 1_440)
    minutes % 60 == 0 ->
        pluralStringResource(R.plurals.reminder_hours, minutes / 60, minutes / 60)
    else -> pluralStringResource(R.plurals.reminder_minutes, minutes, minutes)
}

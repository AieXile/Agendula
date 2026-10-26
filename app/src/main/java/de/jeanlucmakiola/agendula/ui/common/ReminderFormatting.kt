package de.jeanlucmakiola.agendula.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.domain.DAY_MINUTES
import de.jeanlucmakiola.agendula.domain.TaskReminder
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
    minutes % DAY_MINUTES == 0 ->
        pluralStringResource(R.plurals.reminder_days, minutes / DAY_MINUTES, minutes / DAY_MINUTES)
    minutes % 60 == 0 ->
        pluralStringResource(R.plurals.reminder_hours, minutes / 60, minutes / 60)
    else -> pluralStringResource(R.plurals.reminder_minutes, minutes, minutes)
}

/**
 * [reminderLeadTimeLabel] naming the date the lead counts from, for a task
 * that has both: "15 minutes before the start", "On the due date".
 */
@Composable
fun anchoredLeadTimeLabel(minutes: Int, allDay: Boolean, fromStart: Boolean): String {
    @Composable
    fun plural(start: Int, due: Int, count: Int) =
        pluralStringResource(if (fromStart) start else due, count, count)
    return when {
        minutes <= 0 && allDay ->
            stringResource(if (fromStart) R.string.reminder_on_start_date else R.string.reminder_on_due_date)
        minutes <= 0 -> stringResource(if (fromStart) R.string.reminder_at_start else R.string.reminder_at_due_time)
        minutes % 10_080 == 0 ->
            plural(R.plurals.reminder_weeks_before_start, R.plurals.reminder_weeks_before_due, minutes / 10_080)
        minutes % DAY_MINUTES == 0 ->
            plural(R.plurals.reminder_days_before_start, R.plurals.reminder_days_before_due, minutes / DAY_MINUTES)
        minutes % 60 == 0 ->
            plural(R.plurals.reminder_hours_before_start, R.plurals.reminder_hours_before_due, minutes / 60)
        else -> plural(R.plurals.reminder_minutes_before_start, R.plurals.reminder_minutes_before_due, minutes)
    }
}

/** A lead's label, naming its date when [anchored]. */
@Composable
fun reminderLabel(minutes: Int, allDay: Boolean, fromStart: Boolean, anchored: Boolean): String =
    if (anchored) anchoredLeadTimeLabel(minutes, allDay, fromStart) else reminderLeadTimeLabel(minutes, allDay)

/**
 * One of a task's own reminders in one line, naming its date when [anchored].
 * An all-day one names its day and the time it rings at — its own, or
 * [allDayMinuteOfDay] when it has none.
 */
@Composable
fun taskReminderLabel(reminder: TaskReminder, allDay: Boolean, allDayMinuteOfDay: Int, anchored: Boolean): String {
    if (!allDay) return reminderLabel(reminder.minutesBefore, false, reminder.fromStart, anchored)
    val (days, minuteOfDay) = reminder.allDay
    return stringResource(
        R.string.reminder_at_time,
        reminderLabel(days * DAY_MINUTES, true, reminder.fromStart, anchored),
        formatMinuteOfDay(minuteOfDay ?: allDayMinuteOfDay, LocalUse24HourFormat.current),
    )
}

@StringRes
fun reminderAnchorLabel(fromStart: Boolean): Int =
    if (fromStart) R.string.reminder_anchor_start else R.string.reminder_anchor_due

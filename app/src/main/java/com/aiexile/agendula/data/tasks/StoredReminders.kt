package com.aiexile.agendula.data.tasks

import com.aiexile.agendula.data.prefs.Settings
import com.aiexile.agendula.data.reminders.ReminderPlanner
import com.aiexile.agendula.domain.Task
import com.aiexile.agendula.domain.TaskForm
import com.aiexile.agendula.domain.TaskReminder
import java.time.ZoneId
import kotlin.time.Instant

internal object StoredReminders {

    /**
     * The reminders to store for [form], given the task as it was.
     *
     * A date the task gets in this write brings the list's or the app's
     * default for it *as the task's own reminders*. The default is this
     * device's setting, which nothing else can see; stored on the task, it
     * travels with it to the server and fires on every client. The cost is
     * tasks.org's: changing the default later does not reach the tasks that
     * already have it.
     *
     * A task getting its first date takes the defaults only when the form
     * names no reminder; one that already had a date takes those of the new
     * date that no reminder of the form counts from. Otherwise the form is
     * kept, so an edit that clears every reminder means "use the default
     * here" as before.
     *
     * The form's reminders are already of its kind, timed or all-day — see
     * [switched]. Reminders the editor [chose][TaskForm.remindersChosen] are
     * kept as they are: it showed these defaults already.
     */
    fun of(form: TaskForm, current: Task?, settings: Settings, now: Long, zone: ZoneId): List<TaskReminder> {
        val wanted = form.reminders.map { it.lead }.distinct()
        if (form.remindersChosen) return wanted
        return withDefaults(form, current?.start, current?.due, settings, now, zone)
    }

    /** [of] for a task whose dates were [start] and [due], whatever the form chose. */
    fun withDefaults(
        form: TaskForm,
        start: Instant?,
        due: Instant?,
        settings: Settings,
        now: Long,
        zone: ZoneId,
    ): List<TaskReminder> {
        val wanted = form.reminders.map { it.lead }.distinct()
        val had = anchorsOf(start, due)
        val gained = anchorsOf(form.start, form.due) - had
        if (gained.isEmpty()) return wanted
        if (had.isEmpty() || wanted.isEmpty()) return wanted.ifEmpty { defaultsFor(form, settings, now, zone) }
        val named = wanted.map { it.fromStart }.toSet()
        return wanted + defaultsFor(form, settings, now, zone).filter { it.fromStart in gained && it.fromStart !in named }
    }

    /**
     * [reminders] for [form] once it has switched between timed and all-day.
     * Reminders that are exactly the old kind's default become the new kind's;
     * the rest keep what still means something: whole days, rounded to them on
     * an all-day task.
     */
    fun switched(
        reminders: List<TaskReminder>,
        form: TaskForm,
        settings: Settings,
        now: Long,
        zone: ZoneId,
    ): List<TaskReminder> {
        if (reminders.isEmpty()) return reminders
        if (reminders.toSet() == settings.defaultRemindersFor(form.listId, !form.isAllDay).toSet()) {
            return defaultsFor(form, settings, now, zone)
        }
        val kept = if (form.isAllDay) {
            reminders.map { it.inWholeDays() }
        } else {
            reminders.filter { it.allDay.minuteOfDay == null }
        }
        return kept.distinct().ifEmpty { defaultsFor(form, settings, now, zone) }
    }

    /** The dates a reminder can count from, as [TaskReminder.fromStart]. */
    private fun anchorsOf(start: Any?, due: Any?): Set<Boolean> =
        setOfNotNull(true.takeIf { start != null }, false.takeIf { due != null })

    /**
     * The defaults [form] takes: those counting from a date it has. A one-off
     * task leaves out what is already past — a start of "now" would ring at once.
     */
    private fun defaultsFor(form: TaskForm, settings: Settings, now: Long, zone: ZoneId): List<TaskReminder> =
        settings.defaultRemindersFor(form.listId, form.isAllDay).filter { reminder ->
            val anchor = (if (reminder.fromStart) form.start else form.due) ?: return@filter false
            form.rrule != null || ReminderPlanner.triggerAt(
                anchor,
                form.isAllDay,
                reminder.minutesBefore,
                settings.allDayReminderMinuteOfDay,
                zone,
            ) >= now
        }
}

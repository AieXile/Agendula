package de.jeanlucmakiola.agendula.domain

import de.jeanlucmakiola.floret.reminders.ReminderLead
import kotlin.time.Instant

const val DAY_MINUTES = 24 * 60

/**
 * The earliest lead an all-day reminder can have: 00:01 on the day. On an
 * all-day task a lead that is not whole days counts back from the date's
 * midnight, so `-540` is 09:00 on the day — see [TaskReminder.allDay].
 */
const val MIN_ALL_DAY_LEAD = -(DAY_MINUTES - 1)

/** A stored reminder: how long before the start ([fromStart]) or the due date. */
data class TaskReminder(
    val minutesBefore: Int,
    val fromStart: Boolean = false,
    /**
     * When another client last dismissed this alarm (RFC 9074 `ACKNOWLEDGED`):
     * a trigger at or before it has been dealt with and is not fired here.
     * Never part of what a reminder *is* — see [lead].
     */
    val acknowledgedAt: Instant? = null,
) {
    /** The reminder without its bookkeeping, for comparing two of them. */
    val lead: TaskReminder get() = if (acknowledgedAt == null) this else copy(acknowledgedAt = null)

    /** This reminder read as an all-day one. */
    val allDay: AllDayReminder get() = AllDayReminder.of(minutesBefore)

    /** This reminder's lead rounded to the nearest whole day, as the earlier all-day leads were. */
    fun inWholeDays(): TaskReminder =
        copy(minutesBefore = (minutesBefore.coerceAtLeast(0) + DAY_MINUTES / 2) / DAY_MINUTES * DAY_MINUTES)

    companion object {
        /**
         * The all-day reminder [daysBefore] the date at [minuteOfDay], or at the
         * device's all-day time when that is null. Midnight would read back as
         * the latter, so it is 00:01 — see [allDayMinuteOfDay].
         */
        fun allDay(daysBefore: Int, minuteOfDay: Int?, fromStart: Boolean = false) =
            TaskReminder(daysBefore * DAY_MINUTES - (minuteOfDay?.let(::allDayMinuteOfDay) ?: 0), fromStart)

        /** The time an all-day reminder can name: any but midnight, which is a whole-day lead. */
        fun allDayMinuteOfDay(minuteOfDay: Int): Int = minuteOfDay.coerceAtLeast(1)
    }
}

/** An all-day reminder as the day it rings on and, when it names one, its time. */
data class AllDayReminder(val daysBefore: Int, val minuteOfDay: Int?) {
    companion object {
        /** [minutesBefore] on an all-day task; anything after the day is clamped to its last minute. */
        fun of(minutesBefore: Int): AllDayReminder {
            val minutes = minutesBefore.coerceAtLeast(MIN_ALL_DAY_LEAD)
            if (minutes % DAY_MINUTES == 0) return AllDayReminder(minutes / DAY_MINUTES, minuteOfDay = null)
            val days = Math.floorDiv(minutes, DAY_MINUTES) + 1
            return AllDayReminder(days, minuteOfDay = days * DAY_MINUTES - minutes)
        }
    }
}

/** A canonical order, for comparing two sets of reminders. */
val TaskReminderOrder: Comparator<TaskReminder> =
    compareBy<TaskReminder>({ !it.fromStart }, { -it.minutesBefore })

/**
 * The order the editor and the detail screen list reminders in: start's first,
 * then earliest first. An all-day reminder without a time rings at [allDayMinuteOfDay].
 */
fun taskReminderDisplayOrder(allDay: Boolean, allDayMinuteOfDay: Int): Comparator<TaskReminder> =
    compareBy<TaskReminder>({ !it.fromStart }, { reminder ->
        if (!allDay) {
            -reminder.minutesBefore
        } else {
            val (days, minuteOfDay) = reminder.allDay
            (minuteOfDay ?: allDayMinuteOfDay) - days * DAY_MINUTES
        }
    })

/** A default reminder's lead as a reminder of the task's own. */
fun ReminderLead.asTaskReminder(): TaskReminder = TaskReminder(minutes, fromStart)

/** This reminder's lead, as default reminders are kept. */
fun TaskReminder.asReminderLead(): ReminderLead = ReminderLead(minutesBefore, fromStart)

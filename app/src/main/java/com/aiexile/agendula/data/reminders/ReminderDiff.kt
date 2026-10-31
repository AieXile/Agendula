package com.aiexile.agendula.data.reminders

/**
 * What one scheduler pass does to the armed set.
 *
 * `previous` is only what the store *believes* is armed. AlarmManager forgets
 * everything on reboot and force-stop, and a restore brings the store back on a
 * device that never armed any of it, so each entry is checked against `isArmed`
 * first. `fired` entries already went off and count as live, so they are
 * neither re-armed nor re-fired.
 */
internal data class ReminderDiff(
    val cancel: Set<ScheduledReminder>,
    val arm: Set<ScheduledReminder>,
    val keep: Set<ScheduledReminder>,
) {
    companion object {
        fun of(
            previous: Set<ScheduledReminder>,
            desired: Set<ScheduledReminder>,
            fired: Set<ScheduledReminder> = emptySet(),
            isArmed: (ScheduledReminder) -> Boolean,
        ): ReminderDiff {
            val live = previous.filterTo(HashSet()) { it in fired || isArmed(it) }
            return ReminderDiff(
                cancel = live - desired,
                arm = desired - live,
                keep = desired intersect live,
            )
        }
    }
}

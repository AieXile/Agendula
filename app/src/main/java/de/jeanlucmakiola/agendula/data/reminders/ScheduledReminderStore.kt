package de.jeanlucmakiola.agendula.data.reminders

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One armed alarm. A recurring task has many occurrences sharing a [taskId], so
 * the trigger time is part of the identity — keying by task alone would collapse
 * a daily task down to a single reminder.
 */
data class ScheduledReminder(
    val taskId: Long,
    val triggerAt: Long,
    /** The occurrence's `RECURRENCE-ID` anchor in epoch millis, or [NO_OCCURRENCE]. */
    val occurrenceStart: Long = NO_OCCURRENCE,
) {
    /**
     * Request code for this alarm's PendingIntent. Derived from both fields so
     * sibling occurrences don't share (and overwrite) one alarm slot.
     */
    val requestCode: Int get() = (taskId * 31 + triggerAt).hashCode()

    companion object {
        const val NO_OCCURRENCE = -1L
    }
}

/**
 * Remembers which task reminders are currently armed, so [ReminderScheduler] can
 * diff against a fresh computation and touch only the alarms that changed, and
 * which of them already fired. Persisted in DataStore as sets of
 * `taskId|trigger|occurrence` strings.
 */
@Singleton
class ScheduledReminderStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun all(): Set<ScheduledReminder> = read(KEY)

    /** Reminders that went off, and so must not be armed again while still desired. */
    suspend fun fired(): Set<ScheduledReminder> = read(FIRED_KEY)

    /** Whether the last pass could arm exact alarms; null before the first pass that recorded it. */
    suspend fun armedExact(): Boolean? = dataStore.data.first()[EXACT_KEY]

    suspend fun replace(
        scheduled: Set<ScheduledReminder>,
        fired: Set<ScheduledReminder>? = null,
        exact: Boolean? = null,
    ) {
        dataStore.edit { prefs ->
            prefs[KEY] = encode(scheduled)
            if (fired != null) prefs[FIRED_KEY] = encode(fired)
            if (exact != null) prefs[EXACT_KEY] = exact
        }
    }

    suspend fun markFired(reminder: ScheduledReminder) {
        dataStore.edit { prefs ->
            prefs[FIRED_KEY] = prefs[FIRED_KEY].orEmpty() + encode(setOf(reminder))
        }
    }

    private suspend fun read(key: Preferences.Key<Set<String>>): Set<ScheduledReminder> =
        dataStore.data.first()[key].orEmpty().mapNotNull(::decode).toSet()

    internal companion object {
        private val KEY = stringSetPreferencesKey("scheduled_reminders")
        private val FIRED_KEY = stringSetPreferencesKey("fired_reminders")
        private val EXACT_KEY = booleanPreferencesKey("reminders_armed_exact")

        fun encode(set: Set<ScheduledReminder>): Set<String> =
            set.mapTo(HashSet()) { "${it.taskId}|${it.triggerAt}|${it.occurrenceStart}" }

        fun decode(entry: String): ScheduledReminder? {
            val parts = entry.split('|')
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return null
            val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
            val occ = parts.getOrNull(2)?.toLongOrNull() ?: ScheduledReminder.NO_OCCURRENCE
            return ScheduledReminder(id, at, occ)
        }
    }
}

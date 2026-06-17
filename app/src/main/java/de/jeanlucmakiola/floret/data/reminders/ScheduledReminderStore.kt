package de.jeanlucmakiola.floret.data.reminders

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which task reminders are currently scheduled (taskId → trigger time),
 * so [ReminderScheduler] can diff against a fresh computation and cancel only the
 * alarms that changed. Persisted in DataStore as a set of `taskId|trigger` strings.
 */
@Singleton
class ScheduledReminderStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun all(): Map<Long, Long> =
        dataStore.data.first()[KEY].orEmpty().mapNotNull { entry ->
            val parts = entry.split('|')
            val id = parts.getOrNull(0)?.toLongOrNull()
            val at = parts.getOrNull(1)?.toLongOrNull()
            if (id != null && at != null) id to at else null
        }.toMap()

    suspend fun replace(scheduled: Map<Long, Long>) {
        dataStore.edit { prefs ->
            prefs[KEY] = scheduled.entries.map { "${it.key}|${it.value}" }.toSet()
        }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("scheduled_reminders")
    }
}

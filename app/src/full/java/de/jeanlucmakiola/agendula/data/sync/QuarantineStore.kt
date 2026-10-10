package de.jeanlucmakiola.agendula.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import de.jeanlucmakiola.agendula.data.di.SyncStateDataStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How many times each resource has failed, and therefore which ones to skip.
 *
 * ⚠️ Deliberately **not** a backoff. A backoff assumes the failure is transient
 * and asks "how long until I try again"; the failures that matter here are
 * permanent — a body sabre answers 415 for, a contradictory `RRULE`/`EXDATE`
 * pair Nextcloud answers 500 for forever, a 507 the spec forbids retrying at
 * all. The question worth asking is "how many times before I leave this one
 * alone and finish the collection", and the answer is [THRESHOLD].
 *
 * Counts are cleared the moment a resource succeeds, so a genuinely transient
 * failure costs nothing beyond the runs it actually failed in.
 */
@Singleton
class QuarantineStore @Inject constructor(
    @SyncStateDataStore private val dataStore: DataStore<Preferences>,
) {

    /** Current failure counts, keyed by [key]. */
    suspend fun counts(): Map<String, Int> = decode(dataStore.data.first()[KEY].orEmpty())

    private fun decode(entries: Set<String>): Map<String, Int> = entries.mapNotNull { entry ->
        val separator = entry.lastIndexOf(COUNT_SEPARATOR)
        if (separator <= 0) return@mapNotNull null
        val count = entry.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null
        entry.substring(0, separator) to count
    }.toMap()

    /**
     * Applies one account's changes without disturbing anyone else's.
     *
     * ⚠️ Not a whole-map replace. The counts are global — keyed by list, not by
     * account — while `SyncWorker`'s uniqueness is only *per account*, so two
     * accounts can sync at once. Each would snapshot the same global map and the
     * later writer would discard the other's increments and resurrect the
     * counters it had cleared. Re-reading inside `edit`, which DataStore
     * serialises, keeps the read-modify-write atomic.
     *
     * @param updates counts to set, replacing any current value for those keys.
     * @param cleared keys to remove outright, whatever they currently hold.
     */
    suspend fun merge(updates: Map<String, Int>, cleared: Set<String>) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY].orEmpty()).toMutableMap()
            current -= cleared
            current += updates.filterValues { it > 0 }
            prefs[KEY] = current
                .map { (key, count) -> "$key$COUNT_SEPARATOR$count" }
                .toSet()
        }
    }

    /**
     * Forgets every count belonging to [listIds].
     *
     * ⚠️ The keys are global, exactly like the cadence cursors cleared beside
     * them. A list detached from a removed account and re-attached to a new one
     * would otherwise inherit its old counters — and a resource already at
     * [THRESHOLD] is skipped for ever, since a quarantined resource never
     * succeeds and so never clears.
     */
    suspend fun forget(listIds: Set<Long>) {
        if (listIds.isEmpty()) return
        val prefixes = listIds.map { "$it|" }
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY].orEmpty())
                .filterKeys { key -> prefixes.none(key::startsWith) }
            prefs[KEY] = current
                .map { (key, count) -> "$key$COUNT_SEPARATOR$count" }
                .toSet()
        }
    }

    /**
     * Clears the count for one resource in any of [listIds], so the next sync
     * tries it again — the user's "retry" on a quarantined task.
     */
    suspend fun release(listIds: Set<Long>, href: String) {
        val keys = listIds.map { key(it, href) }.toSet()
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY].orEmpty())
            if (current.keys.none { it in keys }) return@edit
            prefs[KEY] = (current - keys)
                .map { (key, count) -> "$key$COUNT_SEPARATOR$count" }
                .toSet()
        }
    }

    companion object {
        /**
         * Attempts before a resource is left alone.
         *
         * Three rather than one: a 502 from a reverse proxy mid-restart and a
         * permanently malformed body arrive as the same outcome, and burning two
         * extra runs is cheaper than quarantining a resource that would have
         * worked.
         */
        const val THRESHOLD = 3

        fun key(listId: Long, href: String) = "$listId|$href"

        private const val COUNT_SEPARATOR = '#'
        private val KEY = stringSetPreferencesKey("sync_quarantine")
    }
}

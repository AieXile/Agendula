package de.jeanlucmakiola.agendula.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import de.jeanlucmakiola.agendula.data.di.SyncStateDataStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * When each collection was last reconciled against a full listing.
 *
 * ⚠️ This is the mitigation for the one RFC 6578 failure that has no signal at
 * all: a token the server still accepts, over a change log it has already
 * pruned, answers `207` with zero changes and no error. Nothing in the protocol
 * distinguishes that from "nothing happened". The only defence is to stop
 * trusting the token periodically and diff a real listing — so the full path is
 * a permanent safety net, not a fallback, and this is its clock.
 *
 * Kept out of Room deliberately: it is scheduling bookkeeping, not user data,
 * and it must never be part of a backup that could restore a stale "we checked
 * recently" into a fresh install.
 */
@Singleton
class SyncCadenceStore @Inject constructor(
    @SyncStateDataStore private val dataStore: DataStore<Preferences>,
) {

    /**
     * Last *scheduled* full reconciliation per list id.
     *
     * ⚠️ Not "the last time a full listing was read". A collection whose server
     * has no `sync-collection` support reads one on every run, and recording
     * each would keep this permanently fresh — so nothing hung off the periodic
     * mark would ever come due again.
     */
    suspend fun lastFullSync(): Map<Long, Instant> =
        dataStore.data.first()[KEY].orEmpty().mapNotNull { entry ->
            val separator = entry.lastIndexOf(SEPARATOR)
            if (separator <= 0) return@mapNotNull null
            val id = entry.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
            val at = entry.substring(separator + 1).toLongOrNull() ?: return@mapNotNull null
            id to Instant.fromEpochSeconds(at)
        }.toMap()

    /** Merges, rather than replacing, so concurrent accounts do not erase each other. */
    suspend fun record(reconciled: Map<Long, Instant>) {
        if (reconciled.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY].orEmpty()
                .mapNotNull { entry ->
                    val separator = entry.lastIndexOf(SEPARATOR)
                    if (separator <= 0) null else entry.substring(0, separator) to entry
                }
                .toMap()
                .toMutableMap()
            reconciled.forEach { (id, at) ->
                current["$id"] = "$id$SEPARATOR${at.epochSeconds}"
            }
            prefs[KEY] = current.values.toSet()
        }
    }

    /** Forgets a list, so a re-added account starts from a full reconciliation. */
    suspend fun forget(listIds: Set<Long>) {
        if (listIds.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[KEY] = prefs[KEY].orEmpty().filterNot { entry ->
                entry.substringBefore(SEPARATOR).toLongOrNull() in listIds
            }.toSet()
        }
    }

    companion object {
        /**
         * How long a sync token is trusted before a full listing is diffed anyway.
         *
         * Long enough that the incremental path still carries almost every sync,
         * short enough that a silently pruned change log is a day's divergence
         * rather than an indefinite one.
         */
        val FULL_RECONCILIATION_INTERVAL: Duration = 24.hours

        private const val SEPARATOR = '@'
        private val KEY = stringSetPreferencesKey("sync_last_full")
    }
}

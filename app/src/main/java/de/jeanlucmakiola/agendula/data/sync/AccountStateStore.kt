package de.jeanlucmakiola.agendula.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import de.jeanlucmakiola.agendula.data.di.SyncStateDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which accounts the server has stopped accepting.
 *
 * Separate from `accounts.last_sync_error` because the two mean different
 * things to the user and to the engine: an error is "this did not work, we will
 * try again", while this is "**we have stopped trying** and only you can change
 * that". Conflating them is how a client ends up retrying a revoked app password
 * on a timer.
 *
 * Lives with the other per-device sync state, and is therefore excluded from
 * backup — see [SyncStateDataStore]. That is also correct on its own terms: the
 * credential does not survive a restore either, so a restored "needs sign-in" is
 * at best redundant and at worst stale.
 */
@Singleton
class AccountStateStore @Inject constructor(
    @SyncStateDataStore private val dataStore: DataStore<Preferences>,
) {

    suspend fun needingSignIn(): Set<Long> = observeNeedingSignIn().first()

    /** Observed, so a 401 during a background sync reaches an open screen. */
    fun observeNeedingSignIn(): Flow<Set<Long>> = dataStore.data.map { prefs ->
        prefs[KEY].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    suspend fun needsSignIn(accountId: Long): Boolean = accountId in needingSignIn()

    suspend fun setNeedsSignIn(accountId: Long, needed: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[KEY].orEmpty().toMutableSet()
            if (needed) current += accountId.toString() else current -= accountId.toString()
            prefs[KEY] = current
            // A later stop is news again.
            if (!needed) prefs[NOTIFIED] = prefs[NOTIFIED].orEmpty() - accountId.toString()
        }
    }

    /**
     * Records that the user has been told [accountId] needs signing in to.
     *
     * @return true the first time per stop, so the notification is posted once
     *   rather than on every run the stopped account refuses.
     */
    suspend fun markSignInNotified(accountId: Long): Boolean {
        var fresh = false
        dataStore.edit { prefs ->
            val notified = prefs[NOTIFIED].orEmpty()
            fresh = accountId.toString() !in notified
            if (fresh) prefs[NOTIFIED] = notified + accountId.toString()
        }
        return fresh
    }

    private companion object {
        val KEY = stringSetPreferencesKey("accounts_needing_sign_in")
        val NOTIFIED = stringSetPreferencesKey("accounts_sign_in_notified")
    }
}

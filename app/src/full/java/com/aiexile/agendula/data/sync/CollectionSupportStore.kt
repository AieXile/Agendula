package com.aiexile.agendula.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.aiexile.agendula.data.di.SyncStateDataStore
import com.aiexile.caldav.CollectionSupport
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What each account's home set answered to OPTIONS, last time we asked.
 *
 * ⚠️ A cache, never the answer. The "new task list" affordance is *hidden*
 * where neither MKCALENDAR nor extended
 * MKCOL exists, and a picker that has to make a network round trip before it can
 * draw a row is a picker that stutters — so the last answer is what it draws
 * with, and [RemoteListRepository] re-asks before it actually writes. A server
 * that gained the capability in an upgrade, or lost it in a config change, is
 * then wrong for exactly one glance rather than for ever.
 */
@Singleton
class CollectionSupportStore @Inject constructor(
    @SyncStateDataStore private val dataStore: DataStore<Preferences>,
) {

    suspend fun get(accountId: Long): CollectionSupport =
        decode(dataStore.data.first()[KEY].orEmpty())[accountId] ?: CollectionSupport.NONE

    /** Asks [ask], records what it said, and hands it back. */
    suspend fun refresh(accountId: Long, ask: () -> CollectionSupport): CollectionSupport {
        val answer = ask()
        dataStore.edit { prefs ->
            // Re-read inside `edit`, which DataStore serialises: two accounts
            // can be asked at once and a snapshot taken outside would drop one.
            val current = decode(prefs[KEY].orEmpty()).toMutableMap()
            current[accountId] = answer
            prefs[KEY] = current.map { (id, support) -> encode(id, support) }.toSet()
        }
        return answer
    }

    suspend fun forget(accountId: Long) {
        dataStore.edit { prefs ->
            prefs[KEY] = decode(prefs[KEY].orEmpty())
                .filterKeys { it != accountId }
                .map { (id, support) -> encode(id, support) }
                .toSet()
        }
    }

    private fun encode(accountId: Long, support: CollectionSupport): String =
        "$accountId|${support.mkCalendar}|${support.extendedMkCol}"

    private fun decode(entries: Set<String>): Map<Long, CollectionSupport> =
        entries.mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size != FIELDS) return@mapNotNull null
            val accountId = parts[0].toLongOrNull() ?: return@mapNotNull null
            accountId to CollectionSupport(
                mkCalendar = parts[1].toBooleanStrictOrNull() ?: return@mapNotNull null,
                extendedMkCol = parts[2].toBooleanStrictOrNull() ?: return@mapNotNull null,
            )
        }.toMap()

    private companion object {
        val KEY = stringSetPreferencesKey("collection_support")
        const val FIELDS = 3
    }
}

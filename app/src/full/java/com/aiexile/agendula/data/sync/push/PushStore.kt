package com.aiexile.agendula.data.sync.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.aiexile.agendula.data.di.SyncStateDataStore
import com.aiexile.caldav.PushSupport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Per synced list: what its server offers for push, and our subscription there.
 * Kept out of Room and backups, since a subscription names this device's endpoint.
 */
@Singleton
class PushStore @Inject constructor(
    @SyncStateDataStore private val dataStore: DataStore<Preferences>,
) {

    data class ListPush(
        val listId: Long,
        /** Null when the server offers no push for the list. */
        val support: PushSupport? = null,
        /** Where our subscription lives on the server; null when there is none. */
        val subscription: String? = null,
        /** The push endpoint [subscription] was registered for. */
        val endpoint: String? = null,
        val expires: Instant? = null,
    )

    suspend fun all(): Map<Long, ListPush> = decode(dataStore.data.first()[KEY].orEmpty())

    fun observe(): Flow<Map<Long, ListPush>> = dataStore.data.map { decode(it[KEY].orEmpty()) }

    /**
     * Records what the last sync read about each list's push support.
     *
     * @return whether anything changed, which is what makes a registration due.
     */
    suspend fun recordSupport(support: Map<Long, PushSupport?>): Boolean {
        if (support.isEmpty()) return false
        var changed = false
        update { current ->
            support.forEach { (listId, found) ->
                val before = current[listId] ?: ListPush(listId)
                if (before.support != found) {
                    changed = true
                    current[listId] = before.copy(support = found)
                }
            }
        }
        return changed
    }

    suspend fun recordSubscription(listId: Long, subscription: String?, endpoint: String?, expires: Instant?) =
        update { current ->
            val before = current[listId] ?: ListPush(listId)
            current[listId] = before.copy(subscription = subscription, endpoint = endpoint, expires = expires)
        }

    suspend fun clearSubscription(listId: Long) = recordSubscription(listId, null, null, null)

    suspend fun forget(listIds: Set<Long>) {
        if (listIds.isEmpty()) return
        update { current -> listIds.forEach(current::remove) }
    }

    private suspend fun update(change: (MutableMap<Long, ListPush>) -> Unit) {
        dataStore.edit { prefs ->
            // Re-read inside `edit`, which DataStore serialises.
            val current = decode(prefs[KEY].orEmpty()).toMutableMap()
            change(current)
            prefs[KEY] = current.values
                .filter { it.support != null || it.subscription != null }
                .map(::encode)
                .toSet()
        }
    }

    private fun encode(push: ListPush): String = listOf(
        push.listId.toString(),
        push.support?.topic,
        push.support?.vapidPublicKey,
        push.subscription,
        push.endpoint,
        push.expires?.epochSeconds?.toString(),
    ).joinToString(SEPARATOR) { it?.let(::escape).orEmpty() }

    private fun decode(entries: Set<String>): Map<Long, ListPush> =
        entries.mapNotNull { entry ->
            val parts = entry.split(SEPARATOR).map { part -> part.takeIf { it.isNotEmpty() }?.let(::unescape) }
            if (parts.size != FIELDS) return@mapNotNull null
            val listId = parts[0]?.toLongOrNull() ?: return@mapNotNull null
            listId to ListPush(
                listId = listId,
                support = parts[1]?.let { PushSupport(topic = it, vapidPublicKey = parts[2]) },
                subscription = parts[3],
                endpoint = parts[4],
                expires = parts[5]?.toLongOrNull()?.let(Instant::fromEpochSeconds),
            )
        }.toMap()

    // Server-chosen strings, escaped so none contains the separator.
    private fun escape(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun unescape(value: String): String = URLDecoder.decode(value, "UTF-8")

    private companion object {
        val KEY = stringSetPreferencesKey("push_lists")
        const val SEPARATOR = "|"
        const val FIELDS = 6
    }
}

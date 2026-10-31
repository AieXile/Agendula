package com.aiexile.agendula.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.aiexile.agendula.data.di.SyncStateDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * One thing a sync did that the user would not otherwise find out about.
 *
 * ⚠️ Conflict policy is **server wins, local edit discarded**, and
 * [SyncReport]'s own doc says the report is "the other half of the decision, not
 * a nice-to-have". Until this existed the other half was a `Log.i` — the edit was
 * gone, nothing in `ui/` read `discardedEdits`, and from where the user sits that
 * is indistinguishable from the app losing their work.
 */
data class SyncNotice(
    val accountId: Long,
    val listName: String,
    val kind: Kind,
    /** The task's title for a discarded edit, the resource's name for a quarantine. */
    val subject: String,
    /**
     * What makes this notice distinct from another about a different task.
     *
     * ⚠️ Carried but never shown. These are stored as a `Set<String>`, and two
     * discarded edits from one run share an account, a list, a cause and a
     * timestamp — so two *untitled* tasks, or two both called "Milk", encoded
     * identically and one of them silently vanished. The notification counted
     * two and the screen listed one. The UID is the only thing that tells them
     * apart, and it is exactly what must not reach the user: opaque text chosen
     * by whoever created the task.
     */
    val key: String,
    /** Why the edit lost. Null for a quarantine, which has no such choice behind it. */
    val cause: DiscardedEdit.Cause?,
    val at: Instant,
) {
    enum class Kind { DISCARDED_EDIT, QUARANTINED }
}

/**
 * What the last syncs destroyed or gave up on, per account, until it is read.
 *
 * The two kinds keep different company, which is why they are written
 * differently:
 *
 * - A **discarded edit** is news. It happened once, it cannot be undone, and a
 *   later clean sync does not make it untrue — so it accumulates and is cleared
 *   only by the user acknowledging it. Replacing the set every run would let a
 *   quiet sync an hour later erase the one thing worth saying.
 * - A **quarantined resource** is a standing condition: one task has stopped
 *   syncing while the rest of its list is fine. It is re-reported on every run
 *   for as long as it holds, so the account's set of them is *replaced* each
 *   time — which is also how it clears itself the moment the resource starts
 *   working again.
 *
 * Lives with the other per-device sync state, and is therefore excluded from
 * backup — see [SyncStateDataStore]. Correct on its own terms too: a restored
 * device has not discarded anything.
 */
@Singleton
class SyncNoticeStore @Inject constructor(
    @SyncStateDataStore private val dataStore: DataStore<Preferences>,
) {

    /** Observed, so a background sync's news reaches a screen that is already open. */
    fun observeAll(): Flow<List<SyncNotice>> = dataStore.data.map { prefs ->
        prefs[KEY].orEmpty().mapNotNull(::decode).sortedByDescending { it.at }
    }

    /**
     * Folds one account's run into the store.
     *
     * @return only what is **new**, which is what a notification may be posted
     *   for. A quarantine already on record is a condition the user has already
     *   been told about, and re-announcing it on every four-hour run would train
     *   them to ignore the one that matters.
     */
    /**
     * @param titles the local title of each quarantined resource, by href.
     *   ⚠️ Not optional decoration. Without it the row read
     *   `a1f9c3e2-….ics`, which is the opaque blob this file refuses to show
     *   for a discarded edit — and "a task has stopped syncing" that does not
     *   say which task is the very failure the feature exists to fix. Absent
     *   only for a resource we never stored, where the filename is genuinely
     *   all there is.
     */
    suspend fun record(
        accountId: Long,
        at: Instant,
        reports: List<SyncReport>,
        titles: Map<String, String> = emptyMap(),
    ): List<SyncNotice> {
        val discarded = reports.flatMap { report ->
            report.discardedEdits.map { edit ->
                SyncNotice(
                    accountId = accountId,
                    listName = report.listName,
                    kind = SyncNotice.Kind.DISCARDED_EDIT,
                    // The UID is not shown to anyone: it is opaque text chosen by
                    // whoever created the task, routinely a bare hex blob.
                    subject = edit.title.orEmpty(),
                    key = edit.uid,
                    cause = edit.cause,
                    at = at,
                )
            }
        }
        // ⚠️ Only the ones that have actually stopped. Below the threshold the
        // resource is still being retried, and "one of your tasks has stopped
        // syncing" would be untrue of a single 502 from a proxy mid-restart.
        val quarantined = reports.flatMap { report ->
            report.quarantined
                .filter { it.failures >= QuarantineStore.THRESHOLD }
                .map { resource ->
                    SyncNotice(
                        accountId = accountId,
                        listName = report.listName,
                        kind = SyncNotice.Kind.QUARANTINED,
                        subject = titles[resource.href] ?: resource.href.substringAfterLast('/'),
                        key = resource.href,
                        cause = null,
                        at = at,
                    )
                }
        }

        // ⚠️ Nothing to say is the overwhelmingly common case — most syncs
        // discard nothing and quarantine nothing — and a DataStore edit rewrites
        // and fsyncs the whole file. Skipped only when there is also nothing on
        // record to clear, or a recovered resource would keep its notice for ever.
        if (discarded.isEmpty() && quarantined.isEmpty() && !hasRecord(accountId)) {
            return emptyList()
        }

        var added = emptyList<SyncNotice>()
        dataStore.edit { prefs ->
            // ⚠️ Re-read inside `edit`, which DataStore serialises. The set is
            // global while `SyncWorker`'s uniqueness is only per account, so two
            // accounts can be folding in at once and a snapshot taken outside
            // would discard the other's.
            val current = prefs[KEY].orEmpty().mapNotNull(::decode)
            val others = current.filter { it.accountId != accountId }
            val keptDiscards = current.filter {
                it.accountId == accountId && it.kind == SyncNotice.Kind.DISCARDED_EDIT
            }
            val standing = current.filter {
                it.accountId == accountId && it.kind == SyncNotice.Kind.QUARANTINED
            }
            added = discarded + quarantined.filterNot { fresh ->
                standing.any { it.key == fresh.key }
            }
            // Newest first, then capped: an account that has been failing for a
            // week must not grow this without bound, and the oldest news is the
            // least actionable.
            val kept = (discarded + keptDiscards).sortedByDescending { it.at }.take(MAX_PER_ACCOUNT)
            // ⚠️ Capped as well, and the class doc used to claim it did not need
            // to be. "Bounded by the collection" is only true of a healthy one:
            // a server answering 415 to four hundred resources puts four hundred
            // entries in one preference key, rewritten on every run — and the
            // account screen renders them into a plain scrolling column.
            val standingNow = quarantined.take(MAX_PER_ACCOUNT)
            val updated = (others + kept + standingNow).map(::encode).toSet()
            // ⚠️ Only when it differs. A DataStore edit rewrites and fsyncs the
            // whole file, and an account holding one un-dismissed notice would
            // otherwise pay that on every four-hour sync until the user tapped
            // "Got it" — which is the cost the fast path above claims to avoid.
            if (updated != prefs[KEY]) prefs[KEY] = updated
        }
        return added
    }

    private suspend fun hasRecord(accountId: Long): Boolean =
        dataStore.data.first().let { prefs ->
            prefs[KEY].orEmpty().mapNotNull(::decode).any { it.accountId == accountId }
        }

    /**
     * Forgets one list's notices, for a list that has just been deleted.
     *
     * By name, because that is how they are keyed — there is no list id in a
     * notice, and by the time this is called the row it would have named is
     * already gone.
     */
    suspend fun forgetList(accountId: Long, listName: String) {
        dataStore.edit { prefs ->
            val kept = prefs[KEY].orEmpty()
                .mapNotNull(::decode)
                .filterNot { it.accountId == accountId && it.listName == listName }
                .map(::encode)
                .toSet()
            if (kept != prefs[KEY]) prefs[KEY] = kept
        }
    }

    /** Drops one quarantine notice, for a resource the user has asked to retry. */
    suspend fun forgetQuarantined(accountId: Long, key: String) {
        dataStore.edit { prefs ->
            val kept = prefs[KEY].orEmpty()
                .mapNotNull(::decode)
                .filterNot {
                    it.accountId == accountId && it.kind == SyncNotice.Kind.QUARANTINED && it.key == key
                }
                .map(::encode)
                .toSet()
            if (kept != prefs[KEY]) prefs[KEY] = kept
        }
    }

    /** The user has read them. */
    suspend fun dismiss(accountId: Long) {
        dataStore.edit { prefs ->
            prefs[KEY] = prefs[KEY].orEmpty()
                .mapNotNull(::decode)
                .filterNot { it.accountId == accountId }
                .map(::encode)
                .toSet()
        }
    }

    /**
     * ⚠️ Base64 around the free text, not a delimiter and a hope. A list is
     * named by its owner and a task is titled by its author, so both can hold
     * any character at all — including whatever separator looked safe.
     */
    private fun encode(notice: SyncNotice): String = listOf(
        notice.accountId.toString(),
        notice.kind.name,
        notice.cause?.name.orEmpty(),
        notice.at.toEpochMilliseconds().toString(),
        base64(notice.listName),
        base64(notice.subject),
        base64(notice.key),
    ).joinToString(SEPARATOR)

    private fun decode(entry: String): SyncNotice? {
        val parts = entry.split(SEPARATOR)
        if (parts.size != FIELDS) return null
        val accountId = parts[0].toLongOrNull() ?: return null
        val kind = SyncNotice.Kind.entries.firstOrNull { it.name == parts[1] } ?: return null
        val at = parts[3].toLongOrNull() ?: return null
        return SyncNotice(
            accountId = accountId,
            listName = unBase64(parts[4]) ?: return null,
            kind = kind,
            subject = unBase64(parts[5]) ?: return null,
            key = unBase64(parts[6]) ?: return null,
            cause = DiscardedEdit.Cause.entries.firstOrNull { it.name == parts[2] },
            at = Instant.fromEpochMilliseconds(at),
        )
    }

    private fun base64(value: String): String =
        Base64.getUrlEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun unBase64(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        val KEY = stringSetPreferencesKey("sync_notices")

        /** Not present in URL-safe Base64, nor in a decimal or an enum name. */
        const val SEPARATOR = "|"
        const val FIELDS = 7

        /** Discarded edits, per account. Quarantines are bounded by the collection. */
        const val MAX_PER_ACCOUNT = 50
    }
}

package com.aiexile.agendula.data.sync

import com.aiexile.caldav.PushSupport

/**
 * What a sync did, and — the part that matters — what it destroyed.
 *
 * ⚠️ Conflict policy is **server wins, local edit discarded**.
 * That policy terminates, which is why it was chosen over forking under a new
 * UID, but on its own it is indistinguishable from data loss: the user's edit is
 * gone and nothing said so. The report is the other half of the decision, not a
 * nice-to-have — [discardedEdits] is why this type exists.
 */
data class SyncReport(
    val listId: Long,
    val listName: String,
    val downloaded: Int = 0,
    val uploaded: Int = 0,
    val deletedRemotely: Int = 0,
    val deletedLocally: Int = 0,
    /** Local edits thrown away because the server's copy was newer. */
    val discardedEdits: List<DiscardedEdit> = emptyList(),
    /** Resources the collection gave up on, so the rest of it could finish. */
    val quarantined: List<QuarantinedResource> = emptyList(),
    /**
     * Writes sent without `If-Match` because the server offers no usable
     * validator. Not an error, but the one case where a concurrent edit can be
     * overwritten without us noticing, so it is said out loud.
     */
    val unconditionalWrites: Int = 0,
    /**
     * Whether this run reconciled against a full listing rather than a change log.
     *
     * ⚠️ Tracked because a token the server accepts over a change log it has
     * already pruned returns 207, zero changes and no error — RFC 6578 gives no
     * signal for it at all. The only mitigation is to reconcile in full on a slow
     * cadence regardless of the token, which means knowing when we last did.
     */
    val reconciledInFull: Boolean = false,
    /**
     * Why the change-log path was abandoned, on a run the full path then
     * completed.
     *
     * Not a [failure]: the collection is reconciled and the user has nothing to
     * act on. Kept because a server that rejects `sync-collection` every time
     * will do it again, and that is worth seeing in a log without it becoming an
     * error in the UI.
     */
    val incrementalNote: String? = null,
    /**
     * The server refused our credentials.
     *
     * ⚠️ Escalates to the whole account and stops it, unlike every other failure
     * here. Nextcloud's brute-force protection throttles and then **429s per
     * source IP**, so a client that keeps retrying a dead app password on a timer
     * takes the user's *other* Nextcloud clients down with it, on that network,
     * and looks from the outside like we broke their server. There is nothing to
     * retry anyway: only the user can fix it.
     */
    val authFailure: Boolean = false,
    /** Set when the collection failed as a whole. The account keeps going. */
    val failure: String? = null,
    /**
     * The collection's own properties were read this run, so [pushSupport] is
     * the server's answer rather than the absence of one.
     */
    val collectionRead: Boolean = false,
    /** WebDAV-Push, as the collection offered it this run. */
    val pushSupport: PushSupport? = null,
) {
    val hadWork: Boolean
        get() = downloaded > 0 || uploaded > 0 || deletedRemotely > 0 || deletedLocally > 0
}

/** One local edit that lost to the server. */
data class DiscardedEdit(
    val uid: String,
    val title: String?,
    val cause: Cause,
) {
    enum class Cause {
        /** The server's copy changed after we last read it. */
        SERVER_NEWER,

        /** The task was deleted on the server while it was edited here. */
        DELETED_ON_SERVER,

        /** Deleted here, but changed on the server after that. The delete lost. */
        DELETE_LOST,
    }
}

/**
 * A resource the collection stopped trying.
 *
 * ⚠️ Quarantine is a **counter, not a backoff**. A single HTTP 400 on one
 * resource has halted all of a user's calendar sync in DAVx5 for weeks; the
 * failure has to be contained to the resource that caused it, and the rest of
 * the collection has to complete.
 */
data class QuarantinedResource(
    val href: String,
    val reason: String,
    val failures: Int,
)

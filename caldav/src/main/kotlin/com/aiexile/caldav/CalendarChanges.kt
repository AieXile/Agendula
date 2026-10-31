package com.aiexile.caldav

import okhttp3.HttpUrl

/** What one `sync-collection` REPORT came back with. */
sealed interface ChangeSet {

    /**
     * One page of changes.
     *
     * A page, not a result: RFC 6578 lets a server truncate and hand back a token
     * that resumes where it stopped, so the caller applies this page's bodies,
     * stores [token], and only then asks for the next.
     */
    data class Page(
        val changed: List<RemoteRef>,
        val removed: List<HttpUrl>,
        /**
         * The token to store **after** this page is applied.
         *
         * ⚠️ Null when the server sent a 207 with no `DAV:sync-token` at all,
         * which happens. That is not an error and not a reason to throw — the
         * changes in this page are still real. It means the next run has no
         * cursor and must reconcile in full.
         */
        val token: String?,
        /**
         * The server stopped early and there is more behind [token].
         *
         * Signalled by a `507` on the collection's **own** href, which is
         * truncation rather than failure — a 507 as the *outer* response status
         * means something else entirely.
         */
        val truncated: Boolean = false,
    ) : ChangeSet

    /**
     * The token is no longer usable and the collection must be reconciled in full.
     *
     * ⚠️ **Invalidation has no status code.** `403` (sabre), `400` (Google),
     * `409` (Radicale) and `412` (Evolution) are all observed in the wild, so the
     * only reliable signal is `DAV:valid-sync-token` in the body on **any** 4xx.
     * Thunderbird matches on 400 alone and therefore never recovers from the 403
     * that most of the self-hosted world emits.
     */
    data object TokenInvalid : ChangeSet

    /** The server will not do `sync-collection` here. Fall back, do not retry. */
    data object Unsupported : ChangeSet

    data class Failed(val reason: String) : ChangeSet
}

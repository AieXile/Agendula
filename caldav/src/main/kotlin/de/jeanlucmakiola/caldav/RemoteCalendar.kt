package de.jeanlucmakiola.caldav

import okhttp3.HttpUrl

/**
 * The remote side of one task collection, as the sync engine needs it.
 *
 * A seam, and a load-bearing one: the reconciliation above this interface is
 * where local edits get discarded, tombstones get swept and conflicts get
 * resolved, and none of that should need a server — or a network — to exercise.
 * [CalendarCollection] is the only production implementation.
 */
interface RemoteCalendar {

    val url: HttpUrl

    fun state(): Result<CalendarCollection.State>

    fun list(): Result<List<RemoteRef>>

    /**
     * One page of changes since [token], or null for a first run.
     *
     * Never throws for anything a server can legitimately answer — a rejected
     * token is [ChangeSet.TokenInvalid], not an exception.
     */
    fun changes(token: String?): ChangeSet

    fun fetch(hrefs: List<HttpUrl>): Result<FetchResult>

    fun create(name: String, iCalendar: String): PutOutcome

    /**
     * Replaces [href]. A null [eTag] writes **unconditionally**, which is only
     * ever correct when the server offers no usable validator at all — see
     * [ETag].
     */
    fun update(href: HttpUrl, eTag: String?, iCalendar: String): PutOutcome

    fun delete(href: HttpUrl, eTag: String?): DeleteOutcome
}

package de.jeanlucmakiola.caldav

import at.bitfire.dav4jvm.property.GetETag
import okhttp3.HttpUrl

/**
 * An entity tag, plus the one bit of it that changes behaviour.
 *
 * ⚠️ A weak tag is not a slightly worse strong tag — it is unusable for what we
 * need one for. RFC 9110 §13.1.1: *"A weak entity-tag cannot be used with
 * If-Match."* So a weak tag must never be persisted as a validator; the resource
 * is re-fetched instead.
 *
 * Weak tags are not exotic. On sabre they are the **default** path unless
 * `Prefer: handling=strict` is sent, and any gzip-compressing nginx, Cloudflare
 * or Traefik in front of the server weakens them regardless of the server
 * software — which is why the calendar client asks for `Accept-Encoding:
 * identity`.
 */
data class ETag(val value: String, val weak: Boolean) {

    /** Whether this tag may be persisted and later sent as `If-Match`. */
    val usable: Boolean get() = !weak && value.isNotBlank()

    override fun toString() = if (weak) "W/\"$value\"" else "\"$value\""

    companion object {
        /**
         * The tag from a parsed `DAV:getetag` property.
         *
         * ⚠️ Not [parse]. `GetETag` has *already* stripped the `W/` marker and
         * recorded it separately, so feeding its [GetETag.eTag] back through a
         * parser reports every weak tag as strong — which is precisely the
         * failure the weak flag exists to prevent.
         */
        fun from(property: GetETag?): ETag? {
            val value = property?.eTag?.takeIf { it.isNotBlank() } ?: return null
            return ETag(value, property.weak == true)
        }

        /** Parses a raw `ETag` **header**; null when absent. */
        fun parse(raw: String?): ETag? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val parsed = GetETag(trimmed)
            val value = parsed.eTag?.takeIf { it.isNotBlank() } ?: return null
            return ETag(value, parsed.weak == true)
        }
    }
}

/** A resource as a listing reports it: an href and an ETag, with no body. */
data class RemoteRef(val href: HttpUrl, val eTag: ETag?)

/**
 * A resource together with the body it was served with.
 *
 * ⚠️ [eTag] is the tag that arrived **in the same response as [iCalendar]**, and
 * only such a pairing is ever stored. A tag taken from a listing and a body
 * taken from a later fetch are not a matched pair, and treating them as one is
 * how the Nextcloud shared-calendar landmine detonates: `CalendarObject::get()`
 * hands back a body it has stripped while leaving the ETag alone, so an
 * unmatched pair lets us `If-Match` a mutilated body over the real one.
 */
data class RemoteResource(val href: HttpUrl, val eTag: ETag?, val iCalendar: String)

/**
 * A resource the server answered for, but did not hand over.
 *
 * @param code the response-level status, or `0` when the server reported
 *   success and supplied no `calendar-data` — the same "no HTTP judgement to
 *   report" convention [PutOutcome.Rejected] uses.
 */
data class FetchFailure(val href: HttpUrl, val code: Int)

/** What a multiget actually returned, and what it did not. */
data class FetchResult(
    val resources: List<RemoteResource>,
    /** Asked for, not answered — the server simply omitted them. */
    val missing: List<HttpUrl>,
    /**
     * Asked for, answered, and refused or empty.
     *
     * Separate from [missing] because "the server said 403" and "the server said
     * nothing" are different facts with different right answers — merging them
     * is the same mistake as merging the three meanings of a 412.
     */
    val failed: List<FetchFailure>,
    /**
     * Answered without being asked for.
     *
     * Real servers do this, so responses are matched against the request rather
     * than trusted, and the strays are counted rather than silently applied.
     */
    val unsolicited: List<HttpUrl>,
)

/** The outcome of a PUT. Every branch here is a different bug if merged with another. */
sealed interface PutOutcome {

    /** Stored, with a strong ETag we can use as the next `If-Match`. */
    data class Stored(val href: HttpUrl, val eTag: ETag) : PutOutcome

    /**
     * Stored, but the ETag was absent or weak.
     *
     * Not an error and not a conflict: the resource is on the server. It has to
     * be re-fetched for a usable validator *and* for the server's canonical
     * body, which may not be the bytes we sent.
     */
    data class StoredNeedsRefetch(val href: HttpUrl) : PutOutcome

    /** 412 against `If-None-Match: *` — something already lives at this name. */
    data class NameTaken(val href: HttpUrl) : PutOutcome

    /** 412 against `If-Match`, and the resource is still there: the server is newer. */
    data object ServerNewer : PutOutcome

    /**
     * 412 against `If-Match`, and the resource is gone.
     *
     * Spec-correct and not a conflict at all — a delete on the server racing an
     * edit here. RFC 9110 requires 412 rather than 404 when the precondition is
     * evaluated, so only a follow-up `HEAD` can tell the two apart.
     */
    data object Vanished : PutOutcome

    /**
     * The server refused this body, and sending it again will not help.
     *
     * Covers 4xx and 5xx alike: `507` MUST NOT be auto-retried (RFC 4918 §11.5)
     * and a contradictory `RRULE`/`EXDATE` pair returns 500 from Nextcloud
     * forever, so neither class earns a retry loop.
     */
    data class Rejected(val code: Int, val message: String) : PutOutcome

    /** The request never got an answer. Transport, not judgement. */
    data class Failed(val reason: String) : PutOutcome
}

/** The outcome of a DELETE. */
sealed interface DeleteOutcome {

    /**
     * Gone from the server.
     *
     * 404 and 410 count as success: the goal was for the resource not to exist,
     * and it does not.
     */
    data object Deleted : DeleteOutcome

    /** 412: the server's copy changed after we last saw it. */
    data object ServerNewer : DeleteOutcome

    data class Rejected(val code: Int, val message: String) : DeleteOutcome

    data class Failed(val reason: String) : DeleteOutcome
}

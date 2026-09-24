package de.jeanlucmakiola.caldav

import at.bitfire.dav4jvm.DavCalendar
import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.Error
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.exception.DavException
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.CalendarData
import at.bitfire.dav4jvm.property.GetCTag
import at.bitfire.dav4jvm.property.GetETag
import at.bitfire.dav4jvm.property.SyncToken
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * One remote task collection, as a set of operations rather than a set of HTTP
 * calls. Every method here returns an outcome; none of them throw for anything a
 * server can legitimately answer.
 *
 * This class holds **no task-domain type and no Android type**, per the module
 * boundary — it deals in `String` bodies of `text/calendar`. Deciding what those
 * bodies mean is the engine's job, one module up.
 */
class CalendarCollection(
    client: OkHttpClient,
    override val url: HttpUrl,
    /** Our WebDAV-Push subscription here, named in `Push-Dont-Notify` on writes. */
    pushRegistration: HttpUrl? = null,
) : RemoteCalendar {

    private val httpClient: OkHttpClient = pushRegistration?.let { registration ->
        val header = WebDavPush.dontNotifyHeader(registration)
        client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    if (request.method in NOTIFYING_METHODS) {
                        request.newBuilder().header("Push-Dont-Notify", header).build()
                    } else {
                        request
                    },
                )
            }
            .build()
    } ?: client

    private val dav get() = DavCalendar(httpClient, url)

    /** What a Depth-0 PROPFIND says about the collection right now. */
    data class State(
        val collection: TaskCollection,
        val ctag: String?,
        val syncToken: String?,
    )

    /**
     * Re-reads the collection's own properties.
     *
     * Worth doing on every sync rather than trusting what account-add recorded:
     * ⚠️ ACL churn is real — a calendar shared with the user can be revoked, or
     * demoted from read-write to read-only, with no notification of any kind. A
     * stale "writable" flag turns every upload into a 403 the user cannot act on,
     * and a stale "not shared" flag disarms the guard that keeps us from writing
     * a mutilated body back over the owner's task.
     */
    override fun state(): Result<State> = runCatching {
        var found: State? = null
        var fromSelf = false
        DavResource(httpClient, url).propfind(
            0,
            *CollectionClassifier.PROPERTIES,
            GetCTag.NAME,
            SyncToken.NAME,
        ) { response, relation ->
            val isSelf = relation == Response.HrefRelation.SELF
            // ⚠️ SELF wins outright and nothing overwrites it. A Depth-0 PROPFIND
            // that also reports a member — which some servers send — would
            // otherwise have that member classified as the collection's own
            // state. The non-SELF branch exists only because a server whose href
            // differs from ours by a trailing slash is classified OTHER, and
            // refusing those outright breaks it against real deployments.
            if (!isSelf && (fromSelf || found != null)) return@propfind

            val collection = CollectionClassifier.classify(response) ?: return@propfind
            found = State(
                collection = collection,
                ctag = response[GetCTag::class.java]?.cTag,
                syncToken = response[SyncToken::class.java]?.token,
            )
            if (isSelf) fromSelf = true
        }
        found ?: throw IOException("$url is no longer a task collection")
    }

    /**
     * Every VTODO resource in the collection, as href + ETag.
     *
     * ⚠️ **No time-range filter.** DAVx5 omits it for the same reason: some
     * servers return nothing at all for a `VTODO` comp-filter that carries one,
     * and a task without `DTSTART` or `DUE` is outside every range by
     * construction — which is most tasks.
     *
     * ⚠️ **No `calendar-data` in this REPORT.** A `calendar-query` that asks for
     * bodies downloads the whole collection on every listing; worse, the bodies
     * would arrive without a matched ETag from the same response. Bodies come
     * from [fetch] only.
     */
    override fun list(): Result<List<RemoteRef>> = runCatching {
        val refs = mutableListOf<RemoteRef>()
        dav.calendarQuery(COMPONENT, null, null) { response, relation ->
            if (relation != Response.HrefRelation.MEMBER) return@calendarQuery
            if (!response.isSuccess()) return@calendarQuery
            refs += RemoteRef(response.href, ETag.from(response[GetETag::class.java]))
        }
        refs
    }

    /**
     * One page of changes since [token] (RFC 6578).
     *
     * ⚠️ **No `DAV:limit`.** Nextcloud regressed it to a localised HTML error
     * page, so asking for a bounded page is how you get an unparseable response
     * instead of a bounded one. Truncation is the server's decision, signalled
     * back through [ChangeSet.Page.truncated].
     *
     * ⚠️ **A removal is a `404` at `DAV:response` level**, not inside a
     * `propstat`. A client that only reads statuses out of propstats sees every
     * deletion as an unremarkable response with no properties and silently keeps
     * the row.
     */
    override fun changes(token: String?): ChangeSet = try {
        val changed = mutableListOf<RemoteRef>()
        val removed = mutableListOf<HttpUrl>()
        var truncated = false

        val properties = DavCollection(httpClient, url).reportChanges(
            syncToken = token,
            infiniteDepth = false,
            limit = null,
            GetETag.NAME,
        ) { response, relation ->
            val code = response.status?.code
            when {
                relation == Response.HrefRelation.SELF ->
                    // On our own href this is truncation, not a failure.
                    if (code == INSUFFICIENT_STORAGE) truncated = true

                code == NOT_FOUND || code == GONE -> removed += response.href

                response.isSuccess() ->
                    changed += RemoteRef(response.href, ETag.from(response[GetETag::class.java]))

                // ⚠️ Neither a success nor a removal — a per-object ACL
                // answering 403, a server failing on its own object with 5xx.
                // Dropping it reports the resource as *unchanged*, so the row
                // keeps whatever it holds until the next full reconciliation
                // notices the ETag differs. Carried as changed with no
                // validator instead: the multiget then produces a real verdict
                // for it, and `fetch` already grades one — the same reasoning
                // error, and the same fix, as its twin next door.
                else -> changed += RemoteRef(response.href, eTag = null)
            }
        }

        ChangeSet.Page(
            changed = changed,
            removed = removed,
            token = properties.filterIsInstance<SyncToken>().firstOrNull()?.token,
            truncated = truncated,
        )
    } catch (e: HttpException) {
        when {
            e.code / 100 == 4 && mentionsInvalidToken(e) -> ChangeSet.TokenInvalid
            e.code in UNSUPPORTED -> ChangeSet.Unsupported
            else -> ChangeSet.Failed("HTTP ${e.code}")
        }
    } catch (e: DavException) {
        ChangeSet.Failed(e.toString())
    } catch (e: IOException) {
        ChangeSet.Failed(e.toString())
    }

    /**
     * ⚠️ The body, not the status. Every observed invalidation status is also a
     * legitimate answer to something else, and every server picks a different
     * one — so the element is the signal and the code is noise.
     */
    private fun mentionsInvalidToken(e: HttpException): Boolean =
        Error.VALID_SYNC_TOKEN in e.errors ||
            e.responseBody?.contains(VALID_SYNC_TOKEN_ELEMENT) == true

    /**
     * Downloads [hrefs] with `calendar-multiget`, in batches.
     *
     * ⚠️ Responses are **matched against the request**. Real servers reply with
     * responses for URLs that were never asked for — a collection's own href, a
     * sibling, occasionally something from another collection entirely — and a
     * client that indexes the response by position or trusts it wholesale will
     * apply one resource's body to another's row.
     */
    override fun fetch(hrefs: List<HttpUrl>): Result<FetchResult> = fetch(hrefs, MULTIGET_BATCH)

    /**
     * What makes two hrefs the same resource.
     *
     * ⚠️ Not the raw `encodedPath`. The request href is *ours*, written into the
     * REPORT body verbatim, and the response href is the server's own spelling
     * of it — a server that answers `/my@dav.ics` for a requested `/my%40dav.ics`
     * is the reason `UrlUtils.equals` exists at all. Compared raw, that resource
     * lands in `unsolicited` *and* in `missing`, its intact body is dropped, and
     * three such runs quarantine it out of the download for good, since nothing
     * on this side can refund the count.
     *
     * `pathSegments` is decoded, so both spellings agree. Keeping it a list, not
     * a joined string, stops `/a%2Fb` colliding with `/a/b`. The trailing slash
     * still distinguishes, exactly as `UrlUtils.equals` refuses to normalise it.
     *
     * ⚠️ The origin is deliberately *not* part of this key, and is checked
     * separately by [answersFor]. The two sides do not share one by
     * construction: the REPORT body carries only our paths, so the reply's href
     * resolves against the collection's *post-redirect* location, while our
     * stored hrefs still carry the origin we had before the redirect. Keying on
     * the origin would put every resource of a redirected collection into both
     * `unsolicited` and `missing`.
     */
    private fun identityOf(url: HttpUrl): List<String> = url.pathSegments

    /**
     * Whether a reply about [answered] can be the resource we asked for at
     * [asked].
     *
     * Same host as the href we asked for, or as the collection we asked it of —
     * the latter covering a redirect we followed. Anything else is a body from
     * somewhere we never spoke to, and applying it would write one host's
     * content into another host's row.
     */
    private fun answersFor(answered: HttpUrl, asked: HttpUrl, collection: HttpUrl): Boolean =
        answered.host.equals(asked.host, ignoreCase = true) ||
            answered.host.equals(collection.host, ignoreCase = true)

    /** [batchSize] is a seam for tests; production always uses [MULTIGET_BATCH]. */
    internal fun fetch(hrefs: List<HttpUrl>, batchSize: Int): Result<FetchResult> {
        val resources = mutableListOf<RemoteResource>()
        val unsolicited = mutableListOf<HttpUrl>()
        val failed = mutableListOf<FetchFailure>()
        val seen = mutableSetOf<HttpUrl>()

        // ⚠️ Per batch, not around the whole loop. A 500 on batch seven of ten
        // used to discard the two hundred resources already parsed and answer
        // `Result.failure`, so the caller recorded a collection failure and
        // re-downloaded everything next run — on a flaky link, for ever. A batch
        // that could not be asked is exactly what `missing` means, and the
        // per-resource verdicts the engine already applies say the rest.
        val batches = hrefs.chunked(batchSize)
        for (batch in batches) {
            val attempt = runCatching {
                val byExactPath = batch.associateBy { it.encodedPath }
                // ⚠️ Only identities that name exactly one request href. Two
                // spellings of one path — `/my%40dav.ics` and `/my@dav.ics` —
                // decode alike but are separate rows here, and silently keeping
                // the last would report the other as missing and eventually
                // quarantine it. Where we cannot tell them apart, the exact
                // spelling is the only honest match.
                val byPath = batch.groupBy { identityOf(it) }
                    .filterValues { it.size == 1 }
                    .mapValues { (_, matches) -> matches.single() }
                val calendar = dav
                calendar.multiget(batch, MIME_ICALENDAR, ICALENDAR_VERSION) { response, relation ->
                    if (relation == Response.HrefRelation.SELF) return@multiget
                    val candidate = byExactPath[response.href.encodedPath]
                        ?: byPath[identityOf(response.href)]
                    val asked = candidate
                        ?.takeIf { answersFor(response.href, it, calendar.location) }
                    if (asked == null) {
                        unsolicited += response.href
                        return@multiget
                    }
                    // Before the checks below, and deliberately: the server did
                    // mention this href, so whatever it said, the href is not
                    // one the server omitted.
                    seen += asked
                    if (!response.isSuccess()) {
                        failed += FetchFailure(asked, response.status?.code ?: 0)
                        return@multiget
                    }
                    val body = response[CalendarData::class.java]?.iCalendar
                    if (body == null) {
                        // Success at the response level, no body. The usual shape
                        // is a per-property refusal — a `propstat` carrying 403
                        // around `calendar-data` — and `Response.properties` drops
                        // non-2xx propstats silently, so the verdict has to be
                        // read back out of them or a deterministic refusal
                        // arrives looking like a transient blank.
                        val refusal = response.propstat.firstOrNull { !it.isSuccess() }
                        failed += FetchFailure(asked, refusal?.status?.code ?: 0)
                        return@multiget
                    }
                    resources += RemoteResource(
                        href = asked,
                        // The tag from *this* response, paired with *this* body.
                        eTag = ETag.from(response[GetETag::class.java]),
                        iCalendar = body,
                    )
                }
            }
            // ⚠️ The first batch to fail ends the fetch, but keeps what came
            // before it. Carrying on would re-ask a server that has just said it
            // cannot answer, and the hrefs left unasked fall out as `missing` —
            // which the engine grades as "listed but not returned" and counts,
            // rather than acting on as a deletion. A whole run that fails on the
            // first batch still answers `failure`, exactly as before.
            if (attempt.isFailure) {
                if (resources.isEmpty() && failed.isEmpty()) {
                    return Result.failure(attempt.exceptionOrNull()!!)
                }
                break
            }
        }

        return Result.success(
            FetchResult(
                resources = resources,
                missing = hrefs.filterNot { it in seen },
                // A server that repeats a response element must not spend two
                // thirds of the quarantine threshold in one run — nor count
                // against a resource it also answered properly.
                failed = failed
                    .filterNot { failure -> resources.any { it.href == failure.href } }
                    .distinctBy { it.href },
                unsolicited = unsolicited,
            ),
        )
    }

    /**
     * Creates a resource at [name] with `If-None-Match: *`.
     *
     * The conditional is what makes creation safe to retry: without it a
     * re-running worker overwrites whatever a previous run put there.
     */
    override fun create(name: String, iCalendar: String): PutOutcome {
        val href = url.newBuilder().addPathSegment(name).build()
        return put(href, iCalendar, ifETag = null, ifNoneMatch = true)
    }

    /**
     * Replaces [href], conditional on [eTag] when one is given.
     *
     * A null [eTag] means the server has never handed us a strong validator for
     * this resource, and a conditional write is therefore impossible rather than
     * merely inconvenient. The engine decides whether writing anyway is
     * acceptable; this class only carries out the decision.
     */
    override fun update(href: HttpUrl, eTag: String?, iCalendar: String): PutOutcome =
        put(href, iCalendar, ifETag = eTag, ifNoneMatch = false)

    private fun put(
        href: HttpUrl,
        iCalendar: String,
        ifETag: String?,
        ifNoneMatch: Boolean,
    ): PutOutcome = try {
        var outcome: PutOutcome = PutOutcome.StoredNeedsRefetch(href)
        DavResource(httpClient, href).put(
            body = iCalendar.toRequestBody(DavCalendar.MIME_ICALENDAR_UTF8),
            ifETag = ifETag,
            ifNoneMatch = ifNoneMatch,
        ) { response ->
            // The server may have stored the resource somewhere else entirely.
            val location = response.header("Location")
                ?.let { href.resolve(it) }
                ?: href
            val eTag = ETag.parse(response.header("ETag"))
            outcome = if (eTag != null && eTag.usable) {
                PutOutcome.Stored(location, eTag)
            } else {
                PutOutcome.StoredNeedsRefetch(location)
            }
        }
        outcome
    } catch (e: HttpException) {
        classifyPutFailure(href, e, ifNoneMatch)
    } catch (e: DavException) {
        // Not an IOException: a refused HTTPS->HTTP redirect arrives here, and
        // it is a configuration problem rather than a transient one.
        PutOutcome.Rejected(0, e.toString())
    } catch (e: IOException) {
        PutOutcome.Failed(e.toString())
    }

    /**
     * ⚠️ **412 means three different things** and merging any two of them is a
     * data-loss bug.
     */
    private fun classifyPutFailure(
        href: HttpUrl,
        e: HttpException,
        wasCreate: Boolean,
    ): PutOutcome = when {
        e.code != PRECONDITION_FAILED -> PutOutcome.Rejected(e.code, e.message.orEmpty())

        // On create the precondition was `If-None-Match: *`, so 412 says only
        // that the name is occupied. It says nothing about by what — the engine
        // re-fetches and adopts the resource if the UID matches.
        wasCreate -> PutOutcome.NameTaken(href)

        // On update the precondition was `If-Match`, and a resource that is gone
        // fails it just as one that changed does. RFC 9110 §13.1.1 requires 412
        // rather than 404 once the precondition is evaluated, so the two are
        // indistinguishable without asking again.
        !exists(href) -> PutOutcome.Vanished

        else -> PutOutcome.ServerNewer
    }

    /**
     * Deletes [href], conditional on [eTag] when we hold a usable one.
     *
     * ⚠️ 404 and 410 are **success**. The purpose of a DELETE is for the
     * resource to be absent, and it is; treating "already gone" as a failure
     * leaves a tombstone that is retried on every sync forever.
     */
    override fun delete(href: HttpUrl, eTag: String?): DeleteOutcome = try {
        DavResource(httpClient, href).delete(ifETag = eTag) { }
        DeleteOutcome.Deleted
    } catch (e: HttpException) {
        when (e.code) {
            NOT_FOUND, GONE -> DeleteOutcome.Deleted
            PRECONDITION_FAILED -> if (exists(href)) {
                DeleteOutcome.ServerNewer
            } else {
                DeleteOutcome.Deleted
            }
            else -> DeleteOutcome.Rejected(e.code, e.message.orEmpty())
        }
    } catch (e: DavException) {
        DeleteOutcome.Rejected(0, e.toString())
    } catch (e: IOException) {
        DeleteOutcome.Failed(e.toString())
    }

    /** A HEAD, used only to tell "changed" from "gone" apart after a 412. */
    private fun exists(href: HttpUrl): Boolean = try {
        DavResource(httpClient, href).head { }
        true
    } catch (e: HttpException) {
        e.code != NOT_FOUND && e.code != GONE
    } catch (_: DavException) {
        true
    } catch (_: IOException) {
        // Unknown. Treat as still present: discarding a local edit is
        // irreversible, and giving up on this resource until the next run is not.
        true
    }

    companion object {
        const val COMPONENT = "VTODO"

        /**
         * Resources per `calendar-multiget`.
         *
         * A whole collection in one REPORT is a request body and a response that
         * a modest server will refuse outright, and a failure mid-way costs the
         * entire batch. DAVx5 settled on the same order of magnitude.
         */
        const val MULTIGET_BATCH = 30

        private const val MIME_ICALENDAR = "text/calendar"
        private const val ICALENDAR_VERSION = "2.0"

        private val NOTIFYING_METHODS = setOf("PUT", "DELETE")

        private const val NOT_FOUND = 404
        private const val GONE = 410
        private const val PRECONDITION_FAILED = 412
        private const val INSUFFICIENT_STORAGE = 507

        /** Matched as a local name, so a server's own namespace prefix is irrelevant. */
        private const val VALID_SYNC_TOKEN_ELEMENT = "valid-sync-token"

        /**
         * The server does not implement the report. Deliberately narrow: 400,
         * 403, 409 and 412 are all *invalidation* codes on some server, so
         * treating them as "unsupported" would abandon `sync-collection` on a
         * collection that merely needs a fresh token.
         */
        private val UNSUPPORTED = setOf(405, 415, 501)
    }
}

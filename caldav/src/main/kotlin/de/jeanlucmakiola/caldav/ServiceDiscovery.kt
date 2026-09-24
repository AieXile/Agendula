package de.jeanlucmakiola.caldav

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One `_caldavs._tcp` SRV record. */
data class SrvRecord(
    val priority: Int,
    val weight: Int,
    val port: Int,
    val target: String,
)

/**
 * DNS lookups for RFC 6764. Behind an interface so the pipeline is testable
 * without a network, and so the resolver can be swapped per platform.
 */
interface DnsResolver {
    fun srv(name: String): List<SrvRecord>
    fun txt(name: String): List<String>

    /** For the base-URL path, where DNS is never consulted. */
    object None : DnsResolver {
        override fun srv(name: String) = emptyList<SrvRecord>()
        override fun txt(name: String) = emptyList<String>()
    }
}

/**
 * Turns what the user typed into the ordered list of URLs worth probing.
 *
 * This is RFC 6764 §6 (`SRV` + `TXT`) followed by the well-known ladder.
 * Every rule below cost
 * somebody a support ticket:
 *
 * - **`TXT path=` is not optional.** Posteo publishes `path=/`, GMX and Web.de
 *   `path=/begenda/dav/users/`. Skipping the TXT lookup lands both on the wrong
 *   path and discovery finds nothing.
 * - **The SRV port is not 443.** Posteo is SRV-only on **8443**, and its
 *   `/.well-known/caldav` 404s. Hardcoding the port fails it outright.
 * - **A `.` target means "explicitly unavailable"** (RFC 2782), not "no record".
 *   `_caldav._tcp.fastmail.com` and `runbox.com` both answer `0 0 0 .`.
 * - **`/` is the last rung**, after the TXT path and `/.well-known/caldav`. The
 *   draft omitted it.
 * - **Priority and weight are honoured**, lowest priority first.
 */
object ServiceDiscovery {

    private const val SRV_SERVICE = "_caldavs._tcp"
    private const val WELL_KNOWN = "/.well-known/caldav"

    /** A URL to probe, and where it came from — the "why" a failure report needs. */
    data class Candidate(val url: HttpUrl, val origin: String)

    /**
     * Hosts whose SRV record leads somewhere that is not a DAV server.
     *
     * Google publishes a valid `_caldavs._tcp` record pointing at
     * `calendar.google.com`, which answers **405** to PROPFIND. A strict RFC 6764
     * client follows it into a dead end for every `@gmail.com` address. Google is
     * out of scope anyway — it supports neither VTODO nor MKCALENDAR — so this is
     * caught at the front rather than surfaced as a baffling 405.
     */
    private val SRV_DEAD_ENDS = setOf("calendar.google.com")

    /**
     * @param input what the user typed: an email address, a `mailto:` URI, or a
     * base URL
     */
    fun candidatesFor(input: String, dns: DnsResolver = DnsResolver.None): List<Candidate> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()

        // A typed URL is taken as typed and DNS is never consulted — a user who
        // gave us an address meant it.
        asBaseUrl(trimmed)?.let { typed ->
            val candidates = mutableListOf<Candidate>()
            // ⚠️ A typed URL is not automatically a *DAV* URL, and the bare
            // origin is what people actually type. `https://cloud.example.com`
            // is the web UI: PROPFIND on it returns the 405 any web server
            // answers, which reads as "not a CalDAV server" about a perfectly
            // good one. RFC 6764 §6 exists precisely for this case, so the
            // well-known probe has to run here too — returning the typed URL as
            // the only candidate is what made a working Nextcloud undiscoverable.
            //
            // ⚠️ Built through `newBuilder`, not by interpolating `host`, which
            // returns an IPv6 literal *without* its brackets — "fd00::1", not
            // "[fd00::1]". Pasting that back into a URL yields a string OkHttp
            // will not parse, so both candidates would be dropped and a typed
            // IPv6 address would report as "not an address". `toString()` also
            // drops a default port on its own.
            val origin = typed.newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
                .toString()
            if (typed.encodedPath.trim('/').isEmpty()) {
                add(candidates, origin, WELL_KNOWN, "typed origin + .well-known")
                add(candidates, origin, "/", "typed origin + root")
            } else {
                // A deep URL may well be the DAV root itself, and PROPFIND on it
                // can return principal, home-set and collection in one response.
                // The well-known stays as the fallback for a path that was a
                // guess.
                candidates += Candidate(typed, "base URL as typed")
                add(candidates, origin, WELL_KNOWN, "typed host + .well-known")
            }
            return candidates
        }

        val domain = domainOf(trimmed) ?: return emptyList()
        val candidates = mutableListOf<Candidate>()

        val srv = dns.srv("$SRV_SERVICE.$domain")
            .filterNot { it.target == "." || it.target.isEmpty() || it.port == 0 }
            // DNS names are case-insensitive and dnsjava returns the wire case, so
            // "Calendar.Google.com." would otherwise walk straight past the guard.
            .filterNot { it.target.trimEnd('.').lowercase() in SRV_DEAD_ENDS }
            // ⚠️ RFC 2782 does not require a target to live inside the domain it
            // was queried for, and this ladder is walked over plain UDP DNS with
            // no DNSSEC — then walked *again* after a 401, with the
            // authenticated client. An out-of-domain target is also one the
            // credential would never be offered to, since CalDavHttp scopes it
            // to the typed address's registrable domain, so following it can
            // only end in a 401 the user cannot act on. Refusing leaves the
            // well-known ladder on the typed domain, which is where a correctly
            // delegated install answers anyway.
            .filter { sharesDomain(it.target, domain) }
            .sortedWith(compareBy({ it.priority }, { -it.weight }))

        // The TXT path applies to the SRV target, and to the bare domain when
        // there is no SRV record — that is the GMX/Web.de shape.
        val txtPath = dns.txt("$SRV_SERVICE.$domain")
            .firstNotNullOfOrNull { record ->
                record.split(' ', ';')
                    .firstOrNull { it.startsWith("path=", ignoreCase = true) }
                    ?.substringAfter('=')
                    ?.takeIf { it.isNotEmpty() }
            }

        val origins = srv.map { record ->
            val host = record.target.trimEnd('.').lowercase()
            // Port 443 is the default and stays implicit; anything else is
            // explicit, which is the whole point of Posteo's 8443.
            val port = if (record.port == 443) "" else ":${record.port}"
            "https://$host$port" to "SRV $host$port"
        }.ifEmpty {
            listOf("https://$domain" to "domain as typed")
        }

        for ((origin, label) in origins) {
            if (txtPath != null) {
                add(candidates, origin, txtPath, "$label + TXT path=$txtPath")
            }
            add(candidates, origin, WELL_KNOWN, "$label + .well-known")
            add(candidates, origin, "/", "$label + root")
        }
        return candidates
    }

    /**
     * Whether an SRV target may stand in for [domain] — the same registrable
     * domain, or the exact host where there is none (an IP literal, a
     * single-label name, a host that *is* a public suffix).
     */
    private fun sharesDomain(target: String, domain: String): Boolean {
        val host = target.trimEnd('.').lowercase()
        val scope = scopeOf(domain) ?: return host.equals(domain, ignoreCase = true)
        return scopeOf(host)?.equals(scope, ignoreCase = true) == true
    }

    /** The boundary `CalDavHttp` scopes a credential by, derived the same way. */
    private fun scopeOf(host: String): String? {
        val literal = if (':' in host) "[$host]" else host
        val url = "https://$literal".toHttpUrlOrNull() ?: return null
        return url.topPrivateDomain() ?: url.host
    }

    private fun add(into: MutableList<Candidate>, origin: String, path: String, label: String) {
        val url = (origin.trimEnd('/') + "/" + path.trimStart('/')).toHttpUrlOrNull() ?: return
        if (into.none { it.url == url }) into += Candidate(url, label)
    }

    /**
     * The server **root** for [input] — the origin, not the DAV path.
     *
     * Nextcloud's Login Flow v2 lives at `index.php/login/v2` off the root, so it
     * needs this rather than a discovered collection URL. A typed base URL is
     * taken as typed; an email address becomes `https://<domain>`.
     */
    fun serverRootFor(input: String): HttpUrl? {
        val trimmed = input.trim()
        asBaseUrl(trimmed)?.let { return it }
        return domainOf(trimmed)?.let { "https://$it".toHttpUrlOrNull() }
    }

    /** The input as a base URL, or null if it is an address rather than a URL. */
    internal fun asBaseUrl(input: String): HttpUrl? {
        if (!input.startsWith("http://", ignoreCase = true) &&
            !input.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return input.toHttpUrlOrNull()
    }

    /** The domain of an email address, a `mailto:` URI, or a bare domain. */
    internal fun domainOf(input: String): String? {
        val withoutScheme = input.removePrefix("mailto:").removePrefix("MAILTO:")
        val domain = withoutScheme.substringAfterLast('@').trim().trimEnd('.')
        return domain.takeIf { it.isNotEmpty() && it.contains('.') && !it.contains('/') }
    }
}

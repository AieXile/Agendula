package com.aiexile.caldav

import okhttp3.HttpUrl

/**
 * Which CalDAV service or server software an account talks to.
 *
 * Read off the two things an account already stores, so nothing extra is asked
 * of the server and no column has to be added: the **host**, which names a
 * hosted service outright, and the **principal URL's path**, whose shape is a
 * fingerprint of the software behind it.
 *
 * [hosted] is what decides how an account is *named* on screen. A hosted
 * service's host is boilerplate (`caldav.fastmail.com` for everyone), so its
 * brand is the name; self-hosted software runs on the user's own host, which is
 * the only thing that tells two of them apart.
 */
enum class CalDavProvider(
    val label: String,
    val hosted: Boolean,
    internal val domains: Set<String> = emptySet(),
    internal val principalMarkers: Set<String> = emptySet(),
) {

    /**
     * ownCloud serves `/remote.php/dav/` too and cannot be told apart from here.
     * Nextcloud is the far commoner of the two and the only one the add flow has
     * a browser login for, so the mark goes to it — and because a self-hosted
     * account is titled by its host, the name "Nextcloud" is never written next
     * to an ownCloud server, only its mark.
     */
    NEXTCLOUD("Nextcloud", hosted = false, principalMarkers = setOf("/remote.php/dav/")),

    BAIKAL("Baïkal", hosted = false, principalMarkers = setOf("/dav.php/")),

    DAVICAL("DAViCal", hosted = false, principalMarkers = setOf("/caldav.php/")),

    SOGO("SOGo", hosted = false, principalMarkers = setOf("/sogo/dav/")),

    FASTMAIL(
        "Fastmail",
        hosted = true,
        domains = setOf("fastmail.com", "fastmail.fm", "messagingengine.com"),
    ),

    ICLOUD("iCloud", hosted = true, domains = setOf("icloud.com", "me.com", "mac.com")),

    GOOGLE("Google", hosted = true, domains = setOf("gmail.com", "googlemail.com", "google.com")),

    MAILBOX_ORG("mailbox.org", hosted = true, domains = setOf("mailbox.org")),

    POSTEO("Posteo", hosted = true, domains = setOf("posteo.de", "posteo.net")),

    ZOHO("Zoho", hosted = true, domains = setOf("zoho.com", "zoho.eu")),

    YANDEX("Yandex", hosted = true, domains = setOf("yandex.ru", "yandex.com")),
    ;

    /**
     * The domain to put in an address hint, for a service the user reaches by
     * email address. Null for software they run themselves, whose host is theirs.
     */
    val primaryDomain: String? get() = domains.firstOrNull()

    companion object {

        /**
         * The services worth offering by name on a picker, in the order they
         * should be offered.
         *
         * Everything identified by domain, plus Nextcloud — picked for its
         * browser sign-in rather than for its host. The rest are self-hosted
         * software recognised *after* discovery, from the principal path, so
         * naming them here would only add rows that all ask the same question.
         *
         * A service that cannot be synced at all sorts to the **end**. It is
         * still listed — people come looking for Google, and an absent row reads
         * as the app being unfinished rather than as Google's own limitation —
         * but a dead row belongs under the live ones, not in the middle of them.
         * The sort is stable, so everything else keeps declaration order.
         */
        val selectable: List<CalDavProvider>
            get() = entries.filter { it.domains.isNotEmpty() || it == NEXTCLOUD }
                .sortedBy { ServerQuirk.forProvider(it)?.isFatal == true }

        /** The service a host belongs to, if it is one we know by name. */
        fun forHost(host: String): CalDavProvider? {
            val lower = host.lowercase().trimEnd('.')
            return entries.firstOrNull { provider ->
                provider.domains.any { lower == it || lower.endsWith(".$it") }
            }
        }

        /** The service behind an email address or a typed URL, if any. */
        fun forInput(input: String): CalDavProvider? {
            val host = ServiceDiscovery.asBaseUrl(input)?.host
                ?: ServiceDiscovery.domainOf(input)
                ?: return null
            return forHost(host)
        }

        /**
         * The provider behind a principal URL: the host first, because a service
         * we know by name is not in doubt, then the path shape.
         */
        fun forPrincipal(url: HttpUrl): CalDavProvider? =
            forHost(url.host) ?: forPath(url.encodedPath)

        private fun forPath(path: String): CalDavProvider? {
            val lower = path.lowercase()
            return entries.firstOrNull { provider ->
                provider.principalMarkers.any { it in lower }
            }
        }
    }
}

package de.jeanlucmakiola.caldav

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The discovery trap table, made executable. Every case here was
 * live-probed against a real provider; each one breaks the obvious
 * implementation.
 */
class ServiceDiscoveryTest {

    private class FakeDns(
        val srv: Map<String, List<SrvRecord>> = emptyMap(),
        val txt: Map<String, List<String>> = emptyMap(),
    ) : DnsResolver {
        override fun srv(name: String) = srv[name].orEmpty()
        override fun txt(name: String) = txt[name].orEmpty()
    }

    private fun urls(input: String, dns: DnsResolver = DnsResolver.None) =
        ServiceDiscovery.candidatesFor(input, dns).map { it.url.toString() }

    @Test
    fun `a typed base URL is used as typed and never triggers DNS`() {
        val dns = FakeDns(srv = mapOf("_caldavs._tcp.example.com" to listOf(SrvRecord(0, 0, 8443, "dav.example.com"))))
        val candidates = urls("https://cloud.example.com/remote.php/dav/", dns)

        // The typed path is tried first and the SRV target is never consulted:
        // a user who gave us an address meant it.
        assertThat(candidates.first()).isEqualTo("https://cloud.example.com/remote.php/dav/")
        assertThat(candidates).doesNotContain("https://dav.example.com:8443/")
        // The well-known follows as a fallback, because a typed path may have
        // been a guess — see `a typed deep URL is tried as typed, first`.
        assertThat(candidates)
            .containsExactly(
                "https://cloud.example.com/remote.php/dav/",
                "https://cloud.example.com/.well-known/caldav",
            ).inOrder()
    }

    @Test
    fun `the well-known ladder ends at the root, not at well-known`() {
        // The draft stopped at /.well-known/caldav; a server that 404s there and
        // serves DAV from / would be undiscoverable.
        assertThat(urls("me@example.com")).containsExactly(
            "https://example.com/.well-known/caldav",
            "https://example.com/",
        ).inOrder()
    }

    @Test
    fun `Posteo is SRV-only on port 8443, and its well-known 404s`() {
        val dns = FakeDns(
            srv = mapOf("_caldavs._tcp.posteo.de" to listOf(SrvRecord(0, 0, 8443, "posteo.de."))),
            txt = mapOf("_caldavs._tcp.posteo.de" to listOf("path=/")),
        )
        // Hardcoding 443 fails Posteo outright.
        assertThat(urls("me@posteo.de", dns)).containsExactly(
            "https://posteo.de:8443/",
            "https://posteo.de:8443/.well-known/caldav",
        ).inOrder()
    }

    @Test
    fun `GMX publishes a TXT path that discovery cannot skip`() {
        val dns = FakeDns(
            txt = mapOf("_caldavs._tcp.gmx.net" to listOf("path=/begenda/dav/users/")),
        )
        // Skip the TXT lookup and GMX lands on the wrong path, finding nothing.
        assertThat(urls("me@gmx.net", dns)).contains("https://gmx.net/begenda/dav/users/")
        assertThat(urls("me@gmx.net", dns).first()).isEqualTo("https://gmx.net/begenda/dav/users/")
    }

    @Test
    fun `a null SRV target means explicitly unavailable, not no record`() {
        // RFC 2782. _caldav._tcp.fastmail.com and runbox.com both answer `0 0 0 .`
        val dns = FakeDns(srv = mapOf("_caldavs._tcp.runbox.com" to listOf(SrvRecord(0, 0, 0, "."))))
        assertThat(urls("me@runbox.com", dns)).containsExactly(
            "https://runbox.com/.well-known/caldav",
            "https://runbox.com/",
        ).inOrder()
    }

    @Test
    fun `Google's SRV record points at something that is not a DAV server`() {
        // calendar.google.com answers 405 to PROPFIND. A strict RFC 6764 client
        // follows it into a dead end for every @gmail.com address.
        val dns = FakeDns(
            srv = mapOf("_caldavs._tcp.gmail.com" to listOf(SrvRecord(5, 0, 443, "calendar.google.com."))),
        )
        assertThat(urls("me@gmail.com", dns)).doesNotContain("https://calendar.google.com/")
    }

    @Test
    fun `an SRV target outside the queried domain is refused`() {
        // Plain UDP DNS, no DNSSEC, and the ladder is walked again after a 401
        // with the authenticated client. The credential is scoped to the typed
        // address's registrable domain anyway, so this target could only ever
        // answer 401.
        val dns = FakeDns(
            srv = mapOf(
                "_caldavs._tcp.example.com" to listOf(SrvRecord(0, 0, 443, "dav.attacker.test.")),
            ),
        )
        val candidates = urls("me@example.com", dns)

        assertThat(candidates).doesNotContain("https://dav.attacker.test/.well-known/caldav")
        // The typed domain's own ladder still runs.
        assertThat(candidates).contains("https://example.com/.well-known/caldav")
    }

    @Test
    fun `a subdomain SRV target is still followed`() {
        val dns = FakeDns(
            srv = mapOf(
                "_caldavs._tcp.example.com" to listOf(SrvRecord(0, 0, 443, "caldav.example.com.")),
            ),
        )
        assertThat(urls("me@example.com", dns))
            .contains("https://caldav.example.com/.well-known/caldav")
    }

    @Test
    fun `SRV priority wins, then weight`() {
        val dns = FakeDns(
            srv = mapOf(
                "_caldavs._tcp.example.com" to listOf(
                    SrvRecord(priority = 20, weight = 0, port = 443, target = "backup.example.com"),
                    SrvRecord(priority = 10, weight = 5, port = 443, target = "low.example.com"),
                    SrvRecord(priority = 10, weight = 90, port = 443, target = "main.example.com"),
                ),
            ),
        )
        assertThat(urls("me@example.com", dns).first()).startsWith("https://main.example.com/")
        assertThat(urls("me@example.com", dns)).containsAtLeast(
            "https://main.example.com/.well-known/caldav",
            "https://low.example.com/.well-known/caldav",
            "https://backup.example.com/.well-known/caldav",
        ).inOrder()
    }

    @Test
    fun `port 443 stays implicit so URLs compare equal`() {
        val dns = FakeDns(srv = mapOf("_caldavs._tcp.example.com" to listOf(SrvRecord(0, 0, 443, "dav.example.com"))))
        assertThat(urls("me@example.com", dns).first()).isEqualTo("https://dav.example.com/.well-known/caldav")
    }

    @Test
    fun `mailto and a bare domain both resolve`() {
        assertThat(ServiceDiscovery.domainOf("mailto:me@example.com")).isEqualTo("example.com")
        assertThat(ServiceDiscovery.domainOf("me@example.com")).isEqualTo("example.com")
        assertThat(ServiceDiscovery.domainOf("example.com")).isEqualTo("example.com")
    }

    @Test
    fun `something that is neither an address nor a URL yields nothing`() {
        assertThat(ServiceDiscovery.candidatesFor("hello")).isEmpty()
        assertThat(ServiceDiscovery.candidatesFor("")).isEmpty()
    }

    @Test
    fun `every candidate carries where it came from`() {
        val dns = FakeDns(txt = mapOf("_caldavs._tcp.gmx.net" to listOf("path=/begenda/dav/users/")))
        assertThat(ServiceDiscovery.candidatesFor("me@gmx.net", dns).map { it.origin })
            .contains("domain as typed + TXT path=/begenda/dav/users/")
    }

    // --- a typed URL still gets the RFC 6764 probe -------------------------

    @Test fun `a typed bare origin still tries well-known`() {
        // ⚠️ The case every user actually types. The origin is the *web UI*, and
        // PROPFIND on it returns the 405 any web server answers — which reads as
        // "not a CalDAV server" about a working Nextcloud. Found against a real
        // server, not by reading.
        val paths = ServiceDiscovery.candidatesFor("https://cloud.example.com")
            .map { it.url.encodedPath }

        assertThat(paths).containsExactly("/.well-known/caldav", "/").inOrder()
    }

    @Test fun `a trailing slash is still a bare origin`() {
        val paths = ServiceDiscovery.candidatesFor("https://cloud.example.com/")
            .map { it.url.encodedPath }

        assertThat(paths).containsExactly("/.well-known/caldav", "/").inOrder()
    }

    @Test fun `a typed deep URL is tried as typed, first`() {
        // A deep URL may be the DAV root itself, where one PROPFIND can return
        // principal, home-set and collection together.
        val paths = ServiceDiscovery.candidatesFor("https://cloud.example.com/remote.php/dav/")
            .map { it.url.encodedPath }

        assertThat(paths.first()).isEqualTo("/remote.php/dav/")
        // …but the path may have been a guess, so the probe stays as a fallback.
        assertThat(paths).contains("/.well-known/caldav")
    }

    @Test fun `a non-default port survives the origin rebuild`() {
        val candidates = ServiceDiscovery.candidatesFor("https://cloud.example.com:8443")

        assertThat(candidates.map { it.url.toString() })
            .containsExactly(
                "https://cloud.example.com:8443/.well-known/caldav",
                "https://cloud.example.com:8443/",
            ).inOrder()
    }

    @Test fun `an IPv6 literal keeps its brackets through the origin rebuild`() {
        // ⚠️ `HttpUrl.host` hands back "fd00::1", not "[fd00::1]", so an origin
        // built by interpolating it is a string OkHttp will not parse — both
        // candidates were silently dropped and a homelab address reported as
        // "not an address".
        val candidates = ServiceDiscovery.candidatesFor("https://[fd00::1]:8443")

        assertThat(candidates.map { it.url.toString() })
            .containsExactly(
                "https://[fd00::1]:8443/.well-known/caldav",
                "https://[fd00::1]:8443/",
            ).inOrder()
    }
}

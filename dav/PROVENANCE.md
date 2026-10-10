# `:dav` — vendored dav4jvm

**Upstream:** [bitfireAT/dav4jvm](https://github.com/bitfireAT/dav4jvm),
tag **2.2.1**, commit `f434c9d19b322228916c106beaebd8634b85ddb1`.
**Licence:** MPL-2.0 (`dav/LICENSE`, verbatim). Every file keeps its upstream
header, as §3.4 requires. Agendula's own code is MIT and unaffected — MPL is
file-level copyleft, which is why this lives in its own module rather than
inside `:app`.

62 source files, ~4,200 lines, plus upstream's 15 test classes.

---

## Why vendored, and why this version

We *vendor rather than depend*. dav4jvm is
published on **JitPack only**, which conflicts with our `FAIL_ON_PROJECT_REPOS`
policy, does not sign artifacts, and rebuilds on demand — so a coordinate is not
immutable. Upstream also shipped two breaking majors nineteen days apart
(3.0.0 on 2026-07-08, 4.0.0 on 2026-07-27).

**2.2.1 is the last OkHttp release.** 3.0.0 deleted the OkHttp package for Ktor,
and 4.x additionally requires **Java 21** bytecode while we target 17 across
`:app` and all of floret-kit. Taking 4.x would mean Ktor (~2.45 MB), the wrong
`guava` flavour, and a Java-target migration — and it would invalidate the whole
auth design, which is written in OkHttp terms throughout
(preemptive Basic via an `Interceptor`, OkHttp stripping `Authorization` on
cross-host redirects, `BasicDigestAuthHandler` because OkHttp has no Digest).

Vendoring at 2.2.1 costs us upstream's later work and makes us responsible for
this tree. What it buys: our own Java target, no JitPack, no Ktor tail, no xpp3
in the APK, and the freedom to fix the defects below rather than route around
them.

**This is a plain JVM module, not an Android library.** The upstream tree has
zero Android imports and must not gain any: this layer is earmarked
for floret-kit's `core-dav`, and Calendula needs the same primitives, so
extraction should stay a file move.

---

## Changes from upstream

Upstream's own test suite is vendored with the code and passes unmodified —
that is what makes these changes safe to make. Our additions live in
`LocalChangesTest`; every other test file is upstream's, untouched.

### 1. `commons-lang3` removed

`HttpUtils.kt` imported `org.apache.commons.lang3.time.DateUtils` for exactly one
call — `DateUtils.parseDate(str, locale, patterns…)`, a loop over format strings.
Replaced with that loop. The dependency is gone; the format list is byte-for-byte
upstream's, comments included.

⚠️ **The loop is not the whole of what `DateUtils` did.** It parsed with a
`ParsePosition` and rejected a pattern unless the *entire* string was consumed;
`SimpleDateFormat.parse(String)` accepts a prefix. Pattern 1 ends in the quoted
**literal** `'GMT'`, so `"Wed, 21 Oct 2015 07:28:00 GMT+02:00"` matches it as a
prefix and the offset is silently discarded — a two-hour error, and precisely the
failure change 2 exists to remove. The replacement requires full consumption.

### 2. ⚠️ HTTP dates were parsed and formatted in the device's local zone

**An upstream defect, not a porting artefact.** `httpDateFormatStr` is
`"EEE, dd MMM yyyy HH:mm:ss 'GMT'"` — the `GMT` is a **quoted literal**, so
`SimpleDateFormat` neither reads nor writes a zone from it, and
`httpDateFormat` never had `timeZone` set. So `formatDate` emitted local time
labelled `GMT`, and `parseDate` read `07:28:00 GMT` as 07:28 *local* — every
`getlastmodified` out by the device's UTC offset, in whichever direction the
user happens to live.

Upstream's `HttpUtilsTest` covers only `fileName()` and never touches dates,
which is why this survived. Fixed by forcing GMT on the formatter and on every
parse attempt; patterns carrying a real `z` still take the zone from the input,
as they must. Covered by `LocalChangesTest`.

### 3. Permanent redirects now reach the caller (`dav4jvm#209`)

`followRedirects` mutated `location` in place for every 3xx and told the caller
nothing, so a caller could not distinguish *"this resource has moved, store the
new URL"* from *"follow this once"*. DAVx5 consequently never rewrites a stored
collection URL after a 301 and re-follows it on every sync; persisting the new
URL ourselves is the fix.

Added `DavResource.permanentLocation`, set only along an unbroken chain of 301 /
308. A temporary hop ends the chain — `301 → 302` means the resource moved to the
301's target and is being served elsewhere *for now*, so persisting the 302's
target would be wrong. `location` still moves for every redirect, unchanged.

It is **cleared at the start of every request**, so it describes the request just
made and never one made earlier through the same object. `DavResource` instances
are reused, and a stale value would have the caller persist a URL that a later
`move()` already superseded.

### 4. `xpp3` is compile-time only

Upstream declares `org.ogce:xpp3` as `api`. Android ships `org.xmlpull.v1` in the
framework, so the 371 KB jar is `compileOnly` here and never reaches the APK. The
unit tests run on a plain JVM, which has no framework, so they get the real
implementation via `testImplementation`.

### 5. `httpDateFormat` is no longer a shared mutable formatter

Upstream exposed a single public `SimpleDateFormat`. It is mutable and not
thread-safe: two workers formatting a header concurrently corrupt each other
through its `Calendar`, and any caller could `setTimeZone` on it and undo change 2
for everyone else. It is now private and built per call. Nothing else in the tree
referenced it.

### 6. `<D:unauthenticated/>` is parsed instead of inferred

`CurrentUserPrincipal.Factory` read only the `<href>` child, so an
unauthenticated body (RFC 5397 §3 — a **200** whose content means the request was
not authenticated) arrived as "property present, href null" — identical to a
conformant-but-empty element, and to a server that omits the property entirely.
A caller inferring rejection from the null href therefore also fires on merely
non-conformant servers, and on a request that carried no credential at all.

The factory now makes one pass over both children (`XmlUtils.processTag` consumes
to the end tag and so cannot be called twice) and reports `unauthenticated`
explicitly. Without it, a rejected credential is indistinguishable from a
successful discovery that found nothing.

### 7. WebDAV-Push properties, backported

2.2.1 predates the WebDAV-Push draft (`https://bitfire.at/webdav-push`).
`property/push/` adds what a client reads: the element names, `P:transports`
(Web Push only, with the server's VAPID key) and `P:topic`, both registered in
`PropertyRegistry`. Modelled on upstream's `property/push`, but cut down: the
request bodies and push messages are built and parsed in `:caldav`, which is the
only caller, so upstream's register/message/trigger property classes are left
out.

### 8. HTTP dates are formatted in `Locale.US`

Change 5 built the formatter with `Locale.ROOT`. A desktop JVM formats that in
English, so every test passed; Android's ICU root locale abbreviates months as
`M01`–`M12`, so on a device `formatDate` produced `Thu, 01 M10 2026 …`. Nothing
used it until WebDAV-Push sent it as `<expires>`, and Nextcloud's `dav_push`
app answered every registration with a 500. `parseDate` already used
`Locale.US`; formatting now matches it.

---

## Build integration

- `:dav`'s tests are a **plain JVM `test` task**. CI runs `testFullDebugUnitTest`,
  which exists only on Android variants, so `.forgejo/workflows/ci.yaml` names
  `:dav:test` explicitly. Without that the vendored suite is compiled by nobody
  and run by nobody, and the safety argument above is void.
- `:app` declares `testFullImplementation(libs.xpp3)`. `compileOnly` is not
  transitive, `:app`'s unit tests run on a plain JVM with no framework, and
  `isReturnDefaultValues = true` makes android.jar's stub factory return `null` —
  so anything touching `XmlUtils` would fail with an unrelated-looking NPE.

---

## What was *not* a defect

Two dav4jvm defects were expected to carry over. Only one of them
exists in this version:

- **"Handles 301/302/307/308 but not 303"** — does not hold for 2.2.1.
  `followRedirects` gates on OkHttp's `Response.isRedirect`, which includes
  `HTTP_SEE_OTHER`, and it re-sends the same method, which is what RFC 6764 §5
  asks for during discovery. Pinned by a test so a future resync cannot lose it
  silently.
- **`dav4jvm#209`** — real, and fixed above as change 3.

---

## Resyncing

Fetch the new tag, diff against `f434c9d`, reapply changes 1–4, run
`./gradlew :dav:test`. If upstream's suite fails, the port is wrong — that is the
entire reason it is vendored alongside the code.

## Change 7 — a same-host HTTPS→HTTP redirect is upgraded, not refused

`DavResource.followRedirects` threw `DavException("Received redirect from HTTPS
to HTTP")` for any downgrade. That is right for a redirect to a *different* host,
which has no innocent reading. It is wrong for the same host, and the same host
is the case that actually occurs.

⚠️ **A Nextcloud behind a TLS-terminating reverse proxy without
`overwriteprotocol` — or without `proxy_set_header X-Forwarded-Proto $scheme` —
builds every redirect with `http://`.** That includes the `/.well-known/caldav`
hop RFC 6764 discovery depends on. The server is entirely functional:
`/remote.php/dav/` answers 401 over HTTPS exactly as it should. But discovery
refuses the downgrade, falls back to a `PROPFIND` on the web root, gets the 405
an ordinary web server returns, and reports "not a CalDAV server" about a working
CalDAV server.

Now: when the redirect target's host matches the current one, the scheme is put
back to `https` and the hop continues. Re-issuing the same host and path over TLS
is *strictly safer* than obeying the redirect as sent, and it preserves the
invariant that matters — credentials never travel in cleartext. A cross-host
downgrade still throws.

Found against a real server, not by reading: `cloud.jeanlucmakiola.de` returns
`301 → http://cloud.jeanlucmakiola.de/remote.php/dav/`.

## Change 8 — the credential is scoped by the public-suffix list, not by a label split

`BasicDigestAuthHandler` gated every request on
`domain.equals(UrlUtils.hostToDomain(request host))`, and `hostToDomain` is a
pure last-two-labels split with no public-suffix knowledge. So a server at
`cloud.example.co.uk` scoped the credential to `co.uk`, one at
`myhome.duckdns.org` to `duckdns.org`, and a self-hoster at `192.168.1.10` to
`1.10`.

⚠️ **The handler adds `Authorization: Basic` preemptively**, before any
challenge, to the first HTTPS request to any host that passes that gate. So the
scope is not merely recorded — it is the set of hosts that receive the app
password unprompted.

That is reachable. `ServiceDiscovery` builds candidate origins from SRV targets
without requiring the target to lie inside the queried domain, over plain UDP
DNS with no DNSSEC. Correct scoping forces an on-path attacker to obtain a
certificate for a name inside the victim's own registrable domain, which is
infeasible; `co.uk` scoping lets them point the SRV at a domain they own and
hold a legitimate certificate for.

Now: `request.url.topPrivateDomain() ?: request.url.host`. OkHttp bundles the
public-suffix list including its private section, so the dynamic-DNS providers
self-hosters actually use are covered. `topPrivateDomain()` is null for an IP
literal, a single-label host, and a host that *is* a public suffix — and null
means *no restriction* to this handler, so it falls back to the exact host.

The caller must derive the scope the same way, which is why
`CalDavHttp.authenticated` changed with it: a mismatch withholds the credential
from every request rather than leaking it.

`UrlUtils.hostToDomain` and its test are left alone — after this it has no
production callers, and keeping it keeps the resync diff small.

## Change 9 — Basic is refused over cleartext even when the server asks for it

`insecurePreemptive` gated only the preemptive branch. A plain-HTTP server
answering 401 with a `Basic` challenge still got
`Authorization: Basic <user:password>` in the clear — the flag's name was
accurate and its coverage was not.

The gate now sits on the Basic *emission*, so both paths are covered by one
condition, and the flag is renamed `insecureBasic` to say what it actually
permits. Digest is deliberately untouched: it never puts the password on the
wire, and refusing it would break a LAN server the day the documented
per-account cleartext opt-in ships.

Not currently reachable in the app — `network_security_config.xml` sets
`cleartextTrafficPermitted="false"`, so OkHttp throws before the request is
written, and nothing passes `allowCleartext = true`. Fixed anyway: `:caldav` is
a plain JVM module earmarked for reuse, where the Android policy does not apply,
and the mitigation would evaporate silently the day that opt-in is wired up.

A refused challenge is also not cached. Recording one we never answer leaves the
handler believing Basic is in play, so the preemptive block is skipped, the
refusal repeats, and the 401 after that reports "Basic credentials didn't work
last time" about a credential that never reached the wire.

⚠️ **Upstream's `BasicDigestAuthHandlerTest.testBasic` was amended** — it
asserted exactly this behaviour, using `http://example.com` with a Basic
challenge and expecting the header. Its URL is now `https://`, and the
cleartext cases it used to cover are pinned explicitly by
`cleartextBasicIsRefusedEvenWhenChallenged`,
`cleartextBasicIsSentWhenExplicitlyAllowed` and `cleartextDigestIsStillAnswered`.
This is the one upstream test this port deliberately changes rather than
inherits.

## Change 10 — a Basic challenge no longer hides the Digest one beside it

The 401 branch scanned `response.challenges()` in a loop and did a non-local
`return null` the moment it saw a `Basic` challenge it had already tried. Because
the handler is installed as a **network interceptor**, `basicAuth` is always
primed preemptively over HTTPS — so that fired on the *first* 401, and a
`Digest` challenge later in the same header was never read at all.

A Baïkal or Apache front end that advertises both and rejects Basic at the
application layer therefore never got a Digest answer, and `SyncEngine` marked
the account as needing sign-in permanently.

The loop now reads every challenge before anything is decided; a scheme already
known not to work is simply not re-offered. Giving up is still the outcome when
nothing usable is left — it happens after the whole header has been looked at
rather than in the middle of it.

## Change 11 — the digest counter is per handler, and per nonce

`clientNonce` and `nonceCount` lived on the companion object while `SyncEngine`
builds **one handler per account**, so two accounts syncing at once interleaved
their `nc` values against each other's nonces. They are instance state now.

The count was also never reset. RFC 7616 §3.4.1 defines `nc` as the count of
requests sent *with that nonce*, starting at 1, so a server that enforces it
(Apache `AuthDigestNcCheck On`, several NAS stacks) answers 401 for a rotated
nonce that arrives with a carried-over count. A new server nonce now restarts
it. The client nonce stays put: it is ours, one per handler, and pairing it with
a restarted count is what the RFC describes.

The tell upstream left behind is that every digest test has to reset the counter
by hand — those four assignments now address the handler rather than the class,
which is the only change to that file.

## Change 12 — `qop` list values are trimmed

`paramValue.split(",")` with no `trim()`. HTTP list syntax allows space around
the separator, so `qop="auth, auth-int"` silently downgraded to `auth`, and a
single spaced value (`qop=" auth"`) matched nothing at all — dropping into the
RFC 2069 legacy branch, which emits a response with no `qop`, `nc` or `cnonce`.
An RFC 7616 server rejects that outright: a permanent 401 against a server
behaving perfectly legally. Values are trimmed and compared case-insensitively.

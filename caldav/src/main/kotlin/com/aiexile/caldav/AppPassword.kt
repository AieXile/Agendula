package com.aiexile.caldav

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Gives an app password back when the account is removed.
 *
 * ⚠️ Without this, **uninstalling never revokes access**. Nextcloud's Login Flow
 * v2 mints a device-specific password that survives the app entirely: it stays
 * listed under Settings → Security → Devices & sessions until the user notices
 * and deletes it by hand, on an entry named after an app that is no longer
 * installed. Minting a credential and then abandoning it is not an acceptable
 * end state for a client that asked for one.
 *
 * Best-effort by design. The account is being removed either way, and a server
 * that is unreachable, or was never a Nextcloud, must not block that.
 */
object AppPassword {

    /** Nextcloud's OCS endpoint for "delete the password I authenticated with". */
    private const val PATH = "ocs/v2.php/core/apppassword"

    /**
     * Derives the OCS root from a Nextcloud principal URL.
     *
     * ⚠️ The principal URL is **not** the server root, and appending to it is the
     * bug this function exists to prevent: a principal is
     * `…/remote.php/dav/principals/users/alice/`, so
     * `principal + "ocs/v2.php/…"` produces a path that 404s on every server,
     * every time, silently — the revocation reads as "attempted" and does
     * nothing at all.
     *
     * Nextcloud mounts WebDAV under `remote.php`, so everything before that
     * segment is the server root, and that is true of a subpath install
     * (`https://host/nextcloud/`) as much as of a root one. Falling back to the
     * origin is right for a server that does not use `remote.php` — it is not a
     * Nextcloud, so the endpoint does not exist there under any path.
     */
    fun ocsRootFor(principal: HttpUrl): HttpUrl {
        val mount = principal.pathSegments.indexOfFirst { it.equals("remote.php", ignoreCase = true) }
        // No `remote.php` means this is not a Nextcloud layout, and guessing a
        // prefix from an arbitrary DAV path would aim the DELETE somewhere
        // unrelated. The origin is the only defensible answer, and on a server
        // without the endpoint it simply 404s.
        val prefix = if (mount < 0) emptyList() else principal.pathSegments.take(mount)
        return principal.newBuilder()
            .encodedPath("/")
            .apply {
                prefix.filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
                // A trailing empty segment keeps this a directory URL, so
                // appending the OCS path cannot fuse onto the last segment.
                if (prefix.any { it.isNotEmpty() }) addPathSegment("")
            }
            .build()
    }

    /**
     * ⚠️ The budget is enforced here because nothing above can enforce it.
     * `execute()` parks on a socket read, which no coroutine cancellation and no
     * `Thread.interrupt` can break — only closing the socket does, which is what
     * `callTimeout` does. Wrapping this call in `withTimeoutOrNull` instead
     * returns only once the read has finished anyway, so the caller waits out
     * the shared client's own budget: 30s *per resolved address*, doubled by the
     * authenticator's retry, plus up to 120s of read timeout. Minutes, for a
     * step whose own doc says it must not block the removal.
     *
     * The shared client has a ceiling of its own, but it is sized for a
     * multiget of a full batch over a slow link — minutes, where a courtesy
     * revocation the user is waiting behind gets seconds.
     *
     * @return true when the server confirmed the revocation. False means the
     *   credential may still exist server-side — the caller carries on regardless.
     */
    fun revoke(
        httpClient: OkHttpClient,
        principal: HttpUrl,
        timeout: Duration = REVOCATION_TIMEOUT,
    ): Boolean = revokeAt(httpClient, ocsRootFor(principal), timeout)

    /**
     * The same revocation, for a caller that already holds the server root.
     *
     * ⚠️ Do not route such a caller through [revoke]. [ocsRootFor] is
     * *principal*-shaped: it looks for `remote.php` and falls back to the bare
     * origin when there is none. A login flow hands back a server base, so a
     * subpath install's `https://host/nextcloud/` would collapse to
     * `https://host/` and the DELETE would 404 on every one of them — attempted,
     * and doing nothing, which is the failure [ocsRootFor] exists to prevent.
     */
    fun revokeAt(
        httpClient: OkHttpClient,
        ocsRoot: HttpUrl,
        timeout: Duration = REVOCATION_TIMEOUT,
    ): Boolean = try {
        val url = ocsRoot.newBuilder().addPathSegments(PATH).build()
        Redirects.follow(
            httpClient.newBuilder()
                // Shares the pool and dispatcher, so this costs nothing.
                .callTimeout(timeout.toJavaDuration())
                .build(),
            Request.Builder()
                .url(url)
                .delete()
                // ⚠️ Not optional. Without this header Nextcloud answers the OCS
                // API with a 401 and a CSRF complaint rather than doing the work,
                // which reads exactly like a wrong password.
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json")
                .build(),
        ).use { it.isSuccessful }
    } catch (_: IOException) {
        // callTimeout throws InterruptedIOException, which lands here.
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    /** What a best-effort courtesy call is worth waiting for. */
    val REVOCATION_TIMEOUT: Duration = 5.seconds
}

package de.jeanlucmakiola.agendula.data.sync

/**
 * Why a sync failed, in classes a user can act on.
 *
 * `accounts.last_sync_error` holds the engine's own words — a collection
 * failure wrapping an exception's `toString()` — which are for logs and never
 * for the screen. This reads the class back out of them.
 */
data class SyncFailure(val kind: Kind, val httpCode: Int? = null) {

    enum class Kind {
        /** The server refused the credentials. */
        SIGN_IN,

        /** DNS, a refused connection, a timeout: nothing answered. */
        UNREACHABLE,

        /** The TLS handshake failed — an untrusted or mismatched certificate. */
        CERTIFICATE,

        /** The server answered, with an error of its own. */
        SERVER,

        /** The account or a list is missing something the sync needs. */
        MISCONFIGURED,

        /** Anything else: the collection did not finish, for a reason we do not name. */
        OTHER,
    }

    companion object {

        fun of(error: String): SyncFailure {
            val code = HTTP_CODE.find(error)?.groupValues?.get(1)?.toIntOrNull()
            return when {
                TLS.any { it in error } -> SyncFailure(Kind.CERTIFICATE)
                AUTH.any { it in error } || code == 401 -> SyncFailure(Kind.SIGN_IN)
                NETWORK.any { it in error } -> SyncFailure(Kind.UNREACHABLE)
                CONFIG.any { it in error } -> SyncFailure(Kind.MISCONFIGURED)
                "ServiceUnavailableException" in error -> SyncFailure(Kind.SERVER, code ?: 503)
                code != null -> SyncFailure(Kind.SERVER, code)
                else -> SyncFailure(Kind.OTHER)
            }
        }

        private val HTTP_CODE = Regex("""\bHTTP (\d{3})\b""")

        private val TLS = listOf(
            "SSLHandshakeException",
            "SSLPeerUnverifiedException",
            "CertPathValidatorException",
            "CertificateException",
            "SSLException",
        )

        private val AUTH = listOf(
            "UnauthorizedException",
            "rejected the credentials",
            "no stored password",
        )

        private val NETWORK = listOf(
            "UnknownHostException",
            "ConnectException",
            "NoRouteToHostException",
            "SocketTimeoutException",
            "InterruptedIOException",
            "SocketException",
            "EOFException",
            "timeout",
        )

        private val CONFIG = listOf(
            "no such account",
            "has no username",
            "has no principal URL",
            "has no collection URL",
        )
    }
}

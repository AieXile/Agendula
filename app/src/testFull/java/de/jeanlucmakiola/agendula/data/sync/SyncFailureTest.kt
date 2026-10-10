package de.jeanlucmakiola.agendula.data.sync

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.data.sync.SyncFailure.Kind
import org.junit.jupiter.api.Test

/** What the accounts screen says about `last_sync_error`, which is written for logs. */
class SyncFailureTest {

    @Test
    fun `a rejected credential asks for the password`() {
        assertThat(kind("collection unavailable: at.bitfire.dav4jvm.exception.UnauthorizedException: HTTP 401 Unauthorized"))
            .isEqualTo(Kind.SIGN_IN)
        assertThat(kind("the server rejected the credentials")).isEqualTo(Kind.SIGN_IN)
    }

    @Test
    fun `a network failure is unreachable`() {
        assertThat(kind("collection unavailable: java.net.UnknownHostException: Unable to resolve host \"cloud\""))
            .isEqualTo(Kind.UNREACHABLE)
        assertThat(kind("listing failed: java.net.SocketTimeoutException: timeout")).isEqualTo(Kind.UNREACHABLE)
        assertThat(kind("download failed: java.net.ConnectException: Failed to connect")).isEqualTo(Kind.UNREACHABLE)
    }

    @Test
    fun `a TLS failure is about the certificate`() {
        assertThat(
            kind("collection unavailable: javax.net.ssl.SSLHandshakeException: java.security.cert.CertPathValidatorException"),
        ).isEqualTo(Kind.CERTIFICATE)
    }

    @Test
    fun `a server error carries its code`() {
        val failure = SyncFailure.of("listing failed: at.bitfire.dav4jvm.exception.HttpException: HTTP 500 Internal Server Error")

        assertThat(failure.kind).isEqualTo(Kind.SERVER)
        assertThat(failure.httpCode).isEqualTo(500)
    }

    @Test
    fun `a missing piece of the account is misconfiguration`() {
        assertThat(kind("account has no principal URL")).isEqualTo(Kind.MISCONFIGURED)
        assertThat(kind("list has no collection URL")).isEqualTo(Kind.MISCONFIGURED)
    }

    @Test
    fun `anything else is not guessed at`() {
        assertThat(kind("the server listed no tasks while 4 are known here — nothing was deleted"))
            .isEqualTo(Kind.OTHER)
    }

    private fun kind(error: String) = SyncFailure.of(error).kind
}

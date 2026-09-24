package de.jeanlucmakiola.caldav

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class CalDavProviderTest {

    @Test
    fun `a hosted service is known by its host`() {
        assertThat(CalDavProvider.forInput("me@fastmail.com")).isEqualTo(CalDavProvider.FASTMAIL)
        assertThat(CalDavProvider.forHost("caldav.icloud.com")).isEqualTo(CalDavProvider.ICLOUD)
        assertThat(CalDavProvider.forHost("dav.mailbox.org")).isEqualTo(CalDavProvider.MAILBOX_ORG)
        assertThat(CalDavProvider.forHost("cloud.example.de")).isNull()
    }

    @Test
    fun `self-hosted software is known by its principal path`() {
        val nextcloud = "https://cloud.example.de/remote.php/dav/principals/users/jo/".toHttpUrl()
        assertThat(CalDavProvider.forPrincipal(nextcloud)).isEqualTo(CalDavProvider.NEXTCLOUD)

        val baikal = "https://dav.example.de/dav.php/principals/jo/".toHttpUrl()
        assertThat(CalDavProvider.forPrincipal(baikal)).isEqualTo(CalDavProvider.BAIKAL)

        val unknown = "https://dav.example.de/jo/".toHttpUrl()
        assertThat(CalDavProvider.forPrincipal(unknown)).isNull()
    }

    @Test
    fun `the host wins over the path`() {
        // Fastmail's principals sit under a path we know nothing about; the host
        // already named the service, so nothing else is consulted.
        val url = "https://caldav.fastmail.com/dav/principals/user/me@fastmail.com/".toHttpUrl()
        assertThat(CalDavProvider.forPrincipal(url)).isEqualTo(CalDavProvider.FASTMAIL)
    }

    @Test
    fun `hosted services are titled by brand, self-hosted ones by host`() {
        assertThat(CalDavProvider.FASTMAIL.hosted).isTrue()
        assertThat(CalDavProvider.NEXTCLOUD.hosted).isFalse()
    }
}

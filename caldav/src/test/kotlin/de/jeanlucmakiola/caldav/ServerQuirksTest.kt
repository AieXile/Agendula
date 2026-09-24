package de.jeanlucmakiola.caldav

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerQuirksTest {

    @Test
    fun `the three providers whose real error is not wrong password`() {
        assertThat(ServerQuirk.forInput("me@fastmail.com"))
            .isEqualTo(ServerQuirk.FASTMAIL_APP_PASSWORD)
        assertThat(ServerQuirk.forInput("me@icloud.com"))
            .isEqualTo(ServerQuirk.ICLOUD_APP_SPECIFIC_PASSWORD)
        assertThat(ServerQuirk.forInput("me@gmail.com"))
            .isEqualTo(ServerQuirk.GOOGLE_UNSUPPORTED)
    }

    @Test
    fun `Google is fatal - it supports neither VTODO nor MKCALENDAR`() {
        assertThat(ServerQuirk.GOOGLE_UNSUPPORTED.isFatal).isTrue()
        assertThat(ServerQuirk.FASTMAIL_APP_PASSWORD.isFatal).isFalse()
    }

    @Test
    fun `subdomains count, and a lookalike domain does not`() {
        assertThat(ServerQuirk.forHost("mail.icloud.com")).isEqualTo(ServerQuirk.ICLOUD_APP_SPECIFIC_PASSWORD)
        assertThat(ServerQuirk.forHost("noticloud.com")).isNull()
        assertThat(ServerQuirk.forHost("icloud.com.example.org")).isNull()
    }

    @Test
    fun `a service that cannot be synced is offered last, not omitted`() {
        val offered = CalDavProvider.selectable
        // Listed, because people go looking for it and an absent row reads as
        // the app being unfinished rather than as Google's own limitation.
        assertThat(offered).contains(CalDavProvider.GOOGLE)
        assertThat(offered.last()).isEqualTo(CalDavProvider.GOOGLE)
        // Everything else keeps declaration order — the sort only moves the
        // dead rows, so Nextcloud stays the first thing offered.
        assertThat(offered.first()).isEqualTo(CalDavProvider.NEXTCLOUD)
        assertThat(offered.none { ServerQuirk.forProvider(it)?.isFatal == true && it != CalDavProvider.GOOGLE })
            .isTrue()
    }

    @Test
    fun `a self-hosted server has no quirk`() {
        assertThat(ServerQuirk.forInput("https://cloud.example.de/remote.php/dav/")).isNull()
        assertThat(ServerQuirk.forInput("me@example.de")).isNull()
    }
}

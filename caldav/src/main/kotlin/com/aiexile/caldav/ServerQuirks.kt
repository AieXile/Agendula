package com.aiexile.caldav

import okhttp3.HttpUrl

/**
 * What we know about a server before talking to it.
 *
 * This exists for one reason: *"wrong password" that is actually "you used your
 * account password"* is the single most common support ticket any CalDAV client
 * inherits. Detecting it at account-add time by domain turns a dead end into one
 * sentence of instruction.
 *
 * The domains themselves live on [CalDavProvider] — one list, so a provider the
 * accounts screen can mark is also a provider this can warn about.
 */
enum class ServerQuirk {

    /** Fastmail: needs an app password, and CalDAV is not on the Basic plan. */
    FASTMAIL_APP_PASSWORD,

    /** iCloud: app-specific password, and 2FA must be on to mint one. */
    ICLOUD_APP_SPECIFIC_PASSWORD,

    /**
     * Google: OAuth2-only, and it supports neither VTODO nor MKCALENDAR — its own
     * documentation says *"Doesn't support VTODO or VJOURNAL data"*. It is
     * not a target, so this is a refusal with an explanation rather than
     * a 401 the user cannot act on.
     */
    GOOGLE_UNSUPPORTED,

    /**
     * Nextcloud's brute-force protection throttles then 429s **per source IP**, so
     * a retry loop on a dead app password takes down the user's other Nextcloud
     * clients on that network. Not domain-detectable; set when a server identifies
     * itself. Kept here so the engine has one place to ask.
     */
    NEXTCLOUD_BRUTE_FORCE_PROTECTED,
    ;

    companion object {

        /** The quirk implied by an email address or a URL host, if any. */
        fun forInput(input: String): ServerQuirk? = CalDavProvider.forInput(input)?.quirk

        fun forHost(host: String): ServerQuirk? = CalDavProvider.forHost(host)?.quirk

        /** What is known about a service the user picked by name rather than typed. */
        fun forProvider(provider: CalDavProvider): ServerQuirk? = provider.quirk

        fun forUrl(url: HttpUrl): ServerQuirk? = forHost(url.host)

        private val CalDavProvider.quirk: ServerQuirk?
            get() = when (this) {
                CalDavProvider.FASTMAIL -> FASTMAIL_APP_PASSWORD
                CalDavProvider.ICLOUD -> ICLOUD_APP_SPECIFIC_PASSWORD
                CalDavProvider.GOOGLE -> GOOGLE_UNSUPPORTED
                CalDavProvider.NEXTCLOUD -> NEXTCLOUD_BRUTE_FORCE_PROTECTED
                else -> null
            }
    }

    /** True when discovery should not even be attempted. */
    val isFatal: Boolean get() = this == GOOGLE_UNSUPPORTED

    /**
     * True when the quirk is an errand the user can actually run — go to the
     * provider, mint an app password, come back — rather than a refusal or a
     * note for the engine.
     *
     * What separates the two is whether there is anything to *do*. Google is a
     * dead end and the brute-force note is never shown, so neither earns the
     * screen that walks through the steps.
     */
    val hasSetupSteps: Boolean
        get() = this == FASTMAIL_APP_PASSWORD || this == ICLOUD_APP_SPECIFIC_PASSWORD
}

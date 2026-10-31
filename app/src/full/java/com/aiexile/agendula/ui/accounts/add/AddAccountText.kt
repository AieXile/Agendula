package com.aiexile.agendula.ui.accounts.add

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aiexile.agendula.R
import com.aiexile.caldav.CalDavDiscovery
import com.aiexile.caldav.ServerQuirk

/**
 * A discovery failure, in words rather than a status code.
 *
 * ⚠️ The server's own message never reaches the screen. The most common failure
 * here is a **405** — what an ordinary web server answers to `PROPFIND`, and so
 * what someone typing their *website* instead of their CalDAV address gets — and
 * "HTTP 405 Method Not Allowed" tells them nothing they can act on. It is also
 * untranslatable, and frequently not in a language they read.
 */
internal val CalDavDiscovery.Outcome.Cause.message: Int
    get() = when (this) {
        CalDavDiscovery.Outcome.Cause.NOT_AN_ADDRESS -> R.string.add_account_error_not_an_address
        CalDavDiscovery.Outcome.Cause.NOT_A_DAV_SERVER -> R.string.add_account_error_not_dav
        CalDavDiscovery.Outcome.Cause.NO_CALENDAR_SUPPORT -> R.string.add_account_error_no_calendar
        CalDavDiscovery.Outcome.Cause.UNREACHABLE -> R.string.add_account_error_unreachable
        CalDavDiscovery.Outcome.Cause.INSECURE -> R.string.add_account_error_insecure
        CalDavDiscovery.Outcome.Cause.NO_CALENDARS -> R.string.add_account_error_no_calendars
        CalDavDiscovery.Outcome.Cause.SERVER_ERROR -> R.string.add_account_error_server
    }

/**
 * The same rule as [message], for the messages the flow itself produces.
 *
 * Kept here rather than on [AddAccountMessage] so the type stays a plain Kotlin
 * one and the resource ids stay where the resources are.
 */
@Composable
internal fun AddAccountMessage.text(): String = when (this) {
    AddAccountMessage.Progress.Discovering ->
        stringResource(R.string.add_account_working_discovering)
    AddAccountMessage.Progress.SigningIn ->
        stringResource(R.string.add_account_working_signing_in)
    AddAccountMessage.Progress.ReadingLists ->
        stringResource(R.string.add_account_working_reading_lists)
    AddAccountMessage.Progress.Saving ->
        stringResource(R.string.add_account_working_saving)
    AddAccountMessage.GoogleUnsupported ->
        stringResource(R.string.add_account_error_google_unsupported)
    AddAccountMessage.AlreadyExists ->
        stringResource(R.string.add_account_error_already_exists)
    AddAccountMessage.ExternalStorage ->
        stringResource(R.string.accounts_external_storage)
    AddAccountMessage.NoUsableLists ->
        stringResource(R.string.add_account_error_no_usable_lists)
    AddAccountMessage.NotSaved -> stringResource(R.string.add_account_error_not_saved)
    AddAccountMessage.KeystoreRefused -> stringResource(R.string.add_account_error_keystore)
    AddAccountMessage.CredentialsRejected ->
        stringResource(R.string.add_account_error_credentials_rejected)
    AddAccountMessage.BrowserApprovalExpired ->
        stringResource(R.string.add_account_browser_error_expired)
    AddAccountMessage.BrowserRateLimited ->
        stringResource(R.string.add_account_browser_error_rate_limited)
    AddAccountMessage.BrowserMaintenance ->
        stringResource(R.string.add_account_browser_error_maintenance)
    AddAccountMessage.BrowserFailed ->
        stringResource(R.string.add_account_browser_error_failed)
    AddAccountMessage.BrowserUnavailable ->
        stringResource(R.string.add_account_browser_error_unavailable)
    is AddAccountMessage.OutsideCredentialScope ->
        stringResource(R.string.add_account_error_cross_domain, host)
    is AddAccountMessage.Quirk -> when (quirk) {
        ServerQuirk.FASTMAIL_APP_PASSWORD ->
            stringResource(R.string.add_account_quirk_hint_fastmail)
        ServerQuirk.ICLOUD_APP_SPECIFIC_PASSWORD ->
            stringResource(R.string.add_account_quirk_hint_icloud)
        // Neither reaches a credentials step: Google is refused before it, and
        // the brute-force note is a pre-flight warning.
        else -> ""
    }
}

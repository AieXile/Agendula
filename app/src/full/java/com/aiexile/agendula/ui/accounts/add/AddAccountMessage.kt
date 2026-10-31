package com.aiexile.agendula.ui.accounts.add

import com.aiexile.caldav.ServerQuirk

/**
 * Something the add-account flow has to tell the user, in a form the UI can
 * translate.
 *
 * ⚠️ A type, not a `String`, and not a `@StringRes Int` either. The flow used to
 * build its sentences in the ViewModel, so a dozen of them shipped in English to
 * every locale — the same defect `CalDavDiscovery.Outcome.Cause` was introduced
 * to fix, which it only ever fixed for the address step. Nothing lints for it:
 * `HardcodedText` reads XML layout attributes, and this app has none. Making the
 * state fields carry this makes a literal a compile error, which is the only
 * guard available.
 *
 * A resource id would work too, but two of these need a format argument and an
 * `Int` accepts any other `Int` — this way the argument travels with the message
 * that needs it, and a test can assert on meaning rather than on prose.
 */
sealed interface AddAccountMessage {

    /** Something is happening, and the step says which. */
    sealed interface Progress : AddAccountMessage {
        data object Discovering : Progress
        data object SigningIn : Progress
        data object ReadingLists : Progress
        data object Saving : Progress
    }

    /** Something went wrong, and the step says what. */
    sealed interface Problem : AddAccountMessage

    data object GoogleUnsupported : Problem
    data object AlreadyExists : Problem
    data object ExternalStorage : Problem
    data object NoUsableLists : Problem
    data object NotSaved : Problem
    data object KeystoreRefused : Problem
    data object CredentialsRejected : Problem
    data object BrowserApprovalExpired : Problem
    data object BrowserRateLimited : Problem
    data object BrowserMaintenance : Problem
    data object BrowserFailed : Problem

    /** No browser could be opened at all — AOSP, GrapheneOS, a locked-down profile. */
    data object BrowserUnavailable : Problem

    /**
     * A home set on a host the credential is not scoped to.
     *
     * The host travels with the message rather than being baked into a sentence,
     * so the translation decides where it goes.
     */
    data class OutsideCredentialScope(val host: String) : Problem

    /** A provider whose real requirement is not "wrong password". */
    data class Quirk(val quirk: ServerQuirk) : Problem
}

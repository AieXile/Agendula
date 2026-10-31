package com.aiexile.agendula.data.sync

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * The account authenticator.
 *
 * Agendula holds no auth tokens — a CalDAV account is a username and an app
 * password, and the password lives in [CredentialStore], not here.
 * `AccountManager` stores passwords as plain `TEXT`; there is no encryption or
 * hashing anywhere in AOSP, so nothing secret is handed to it.
 *
 * It is **not** required by any
 * provider — that argument was circular. The real reasons: a stable account
 * identity a third-party engine could address, presence in system Settings, and
 * the sync framework as a change trigger.
 */
class SyncAuthenticator(private val context: Context) : AbstractAccountAuthenticator(context) {

    /**
     * ⚠️ Refuses until the account-add UI exists.
     *
     * The authenticator service is exported and registered, so Settings →
     * Accounts → Add account lists Agendula **today**. Handing back an intent to
     * a screen that does not yet handle [ACTION_ADD_ACCOUNT] would open the
     * ordinary home screen while Settings waits forever on a response nothing
     * answers. A refusal the user can read is strictly better than a hang; 2d
     * replaces this with the real intent and answers [response].
     */
    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle = unsupported("Add a CalDAV account from inside Agendula, under Settings")

    override fun editProperties(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
    ): Bundle = Bundle()

    /**
     * ⚠️ Never `null`. `AbstractAccountAuthenticator.Transport` reads a null
     * return as "I will answer asynchronously via the response", and nothing here
     * ever does — the caller's `AccountManagerFuture` would never complete.
     */
    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?,
    ): Bundle = unsupported("Agendula does not confirm credentials from the system UI")

    /** No token type: this is Basic/Digest against a CalDAV server. */
    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = unsupported("Agendula accounts do not use auth tokens")

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    /** Never `null`, for the reason given on [confirmCredentials]. */
    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = unsupported("Re-authenticate from inside Agendula, under Settings")

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?,
    ): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }

    private fun unsupported(message: String) = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
        putString(AccountManager.KEY_ERROR_MESSAGE, message)
    }

    companion object {
        /** Sent to `MainActivity` when the system asks us to add an account (chunk 2d). */
        const val ACTION_ADD_ACCOUNT = "com.aiexile.agendula.ADD_ACCOUNT"
    }
}

/**
 * Binds [SyncAuthenticator] for the system.
 *
 * Exported and guarded by `android.permission.ACCOUNT_MANAGER` — note that
 * `android.permission.ACCOUNT_AUTHENTICATOR`, which the obvious guess would
 * reach for, **does not exist**.
 */
class AuthenticatorService : Service() {

    private val authenticator by lazy { SyncAuthenticator(this) }

    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}

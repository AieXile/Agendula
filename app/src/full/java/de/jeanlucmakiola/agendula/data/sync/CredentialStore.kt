package de.jeanlucmakiola.agendula.data.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.jeanlucmakiola.agendula.data.di.CredentialsDataStore
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App passwords, encrypted with a hardware-backed Keystore key.
 *
 * `androidx.security:security-crypto` is **formally deprecated and terminal** —
 * deprecated at 1.1.0-alpha07, shipped deprecated in stable 1.1.0, with release
 * notes saying there will be no further releases — and its successor
 * `datastore-tink` is alpha. So: Keystore `AES/GCM/NoPadding` directly, blob in
 * DataStore.
 *
 * Be honest about what this buys. `AccountManager` stores passwords as plain
 * `TEXT` — there is no encryption or hashing anywhere in AOSP — so file-based
 * encryption plus a same-signature check is the whole boundary there. That is
 * DAVx5's posture and it is defensible, but it is not secure storage. This is
 * better, and the difference is worth the ~80 lines.
 *
 * Three deliberate non-choices:
 * - `setUserAuthenticationRequired` is left at its default of `false`. Requiring
 *   a device unlock per decryption makes background sync impossible.
 * - `setUnlockedDeviceRequired` is **not** set, for the same reason.
 * - A failure to decrypt is *never* a crash. It means re-authenticate.
 */
@Singleton
class CredentialStore @Inject constructor(
    @CredentialsDataStore private val dataStore: DataStore<Preferences>,
) {

    /** What came back for an account. */
    sealed interface Secret {
        data class Present(val value: String) : Secret

        data object Absent : Secret

        /**
         * The ciphertext exists but can no longer be decrypted, so the only
         * recovery is to sign in again.
         *
         * Reached by a restored backup (Keystore keys are non-exportable, so a
         * restored blob is permanently undecryptable — which is why the blob is
         * excluded from backup), by the key being invalidated when the user
         * changes their lock screen, or by corruption.
         */
        data class Unrecoverable(val reason: String) : Secret
    }

    /**
     * Stores [appPassword] for [accountId].
     *
     * @return false when the Keystore could not be used at all. A wedged or
     * degraded keystore throws [ProviderException], which is a `RuntimeException`
     * and would otherwise take down the account-add flow — the same "never
     * crash over this" rule [get] follows.
     */
    suspend fun put(accountId: Long, appPassword: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        // The IV travels with the ciphertext. GCM requires a unique IV per
        // encryption under the same key; letting the provider generate it is the
        // only way to be sure of that.
        val payload = cipher.iv + cipher.doFinal(appPassword.toByteArray(Charsets.UTF_8))
        dataStore.edit { it[keyFor(accountId)] = Base64.getEncoder().encodeToString(payload) }
        true
    } catch (e: GeneralSecurityException) {
        false
    } catch (e: ProviderException) {
        false
    } catch (e: IOException) {
        false
    }

    suspend fun get(accountId: Long): Secret {
        val stored = dataStore.data.first()[keyFor(accountId)] ?: return Secret.Absent
        return try {
            val payload = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES),
                )
            }
            Secret.Present(
                String(
                    cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES),
                    Charsets.UTF_8,
                ),
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            // The lock screen changed, or the key was otherwise invalidated.
            Secret.Unrecoverable(e.message ?: "the encryption key was invalidated")
        } catch (e: AEADBadTagException) {
            // Wrong key or tampered ciphertext — the restored-backup case.
            Secret.Unrecoverable(e.message ?: "the stored credential could not be decrypted")
        } catch (e: GeneralSecurityException) {
            Secret.Unrecoverable(e.message ?: "the stored credential could not be read")
        } catch (e: IllegalArgumentException) {
            // Not valid Base64 at all — a truncated or hand-edited blob.
            Secret.Unrecoverable(e.message ?: "the stored credential is malformed")
        } catch (e: ProviderException) {
            // ⚠️ AndroidKeyStore signals keystore-level failure ("Keystore
            // operation failed", "Failed to load key") with this — a
            // RuntimeException, so none of the catches above match it. On a
            // device with a degraded keystore it would crash the sync worker
            // instead of prompting a re-authentication.
            Secret.Unrecoverable(e.message ?: "the device keystore is unavailable")
        } catch (e: IOException) {
            // KeyStore.load declares it.
            Secret.Unrecoverable(e.message ?: "the device keystore could not be opened")
        }
    }

    suspend fun clear(accountId: Long) {
        dataStore.edit { it.remove(keyFor(accountId)) }
    }

    /** Every stored credential. Used when the last account goes away. */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    private fun keyFor(accountId: Long) = stringPreferencesKey("caldav_app_password_$accountId")

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // Not calling setUserAuthenticationRequired /
                    // setUnlockedDeviceRequired is the point — see the class doc.
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "agendula.caldav.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

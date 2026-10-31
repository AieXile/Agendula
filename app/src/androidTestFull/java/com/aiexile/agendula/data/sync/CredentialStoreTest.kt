package com.aiexile.agendula.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * The credential store, against a real Keystore.
 *
 * Instrumented rather than Robolectric because the thing under test *is* the
 * platform: a shadowed Keystore would encrypt and decrypt happily and prove
 * nothing about whether the key spec is usable for background sync.
 *
 * The case that matters most is the last one. A restored backup carries the
 * ciphertext but not the key — Keystore keys are non-exportable — so the blob
 * becomes permanently undecryptable. That must surface as "sign in again", never
 * as a crash and never as a silently non-syncing account, which is why
 * `backup_rules.xml` excludes this file in the first place.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {

    @get:Rule val testName = TestName()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: CredentialStore

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // ⚠️ A file per test, and a scope we can cancel. DataStore's FileStorage
        // keeps a process-wide set of active files and refuses a second
        // connection to one ("There are multiple DataStores active for the same
        // file"); the entry is released only when the owning scope's job
        // completes, and the factory's default scope is never cancelled. Sharing
        // one file across methods therefore fails every test after the first.
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            context.preferencesDataStoreFile("credential_store_test_${testName.methodName}")
        }
        store = CredentialStore(dataStore)
    }

    @After
    fun tearDown() {
        runTest { store.clearAll() }
        scope.cancel()
        context.preferencesDataStoreFile("credential_store_test_${testName.methodName}").delete()
    }

    @Test
    fun anAppPasswordRoundTrips() = runTest {
        assertThat(store.put(accountId = 1L, appPassword = "s3cret-app-pw")).isTrue()
        assertThat(store.get(1L)).isEqualTo(CredentialStore.Secret.Present("s3cret-app-pw"))
    }

    @Test
    fun aNonLatin1PasswordSurvives() = runTest {
        // The same charset trap the Basic interceptor has: anything that silently
        // mangles "ä" produces a 401 the user reads as a wrong password.
        store.put(accountId = 1L, appPassword = "pä§§wörd-🔐")
        assertThat(store.get(1L)).isEqualTo(CredentialStore.Secret.Present("pä§§wörd-🔐"))
    }

    @Test
    fun accountsDoNotShareACredential() = runTest {
        store.put(1L, "first")
        store.put(2L, "second")
        assertThat(store.get(1L)).isEqualTo(CredentialStore.Secret.Present("first"))
        assertThat(store.get(2L)).isEqualTo(CredentialStore.Secret.Present("second"))
    }

    @Test
    fun anUnknownAccountIsAbsentRatherThanAnError() = runTest {
        assertThat(store.get(99L)).isEqualTo(CredentialStore.Secret.Absent)
    }

    @Test
    fun clearingRemovesOnlyThatAccount() = runTest {
        store.put(1L, "first")
        store.put(2L, "second")
        store.clear(1L)
        assertThat(store.get(1L)).isEqualTo(CredentialStore.Secret.Absent)
        assertThat(store.get(2L)).isEqualTo(CredentialStore.Secret.Present("second"))
    }

    @Test
    fun aCiphertextThisDeviceCannotDecryptMeansReAuthenticate() = runTest {
        // Stands in for the restored-backup case: the blob is present and
        // well-formed Base64, but was not produced by this device's key.
        dataStore.edit {
            it[stringPreferencesKey("caldav_app_password_1")] =
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        }
        assertThat(store.get(1L)).isInstanceOf(CredentialStore.Secret.Unrecoverable::class.java)
    }

    @Test
    fun aBlobThatIsNotBase64AtAllIsAlsoRecoverable() = runTest {
        dataStore.edit { it[stringPreferencesKey("caldav_app_password_1")] = "not base64 !!" }
        assertThat(store.get(1L)).isInstanceOf(CredentialStore.Secret.Unrecoverable::class.java)
    }
}

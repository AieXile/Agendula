package de.jeanlucmakiola.agendula.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The sign-in prompt is posted once per stop, not on every refused run. */
class AccountStateStoreTest {

    @TempDir lateinit var directory: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(directory, "state.preferences_pb") }
    }

    private val store by lazy { AccountStateStore(dataStore) }

    @AfterEach fun tearDown() = scope.cancel()

    @Test
    fun `a stopped account is announced once`() = runTest {
        store.setNeedsSignIn(1, true)

        assertThat(store.markSignInNotified(1)).isTrue()
        assertThat(store.markSignInNotified(1)).isFalse()
    }

    @Test
    fun `signing in again makes the next stop news again`() = runTest {
        store.setNeedsSignIn(1, true)
        store.markSignInNotified(1)
        store.setNeedsSignIn(1, false)
        store.setNeedsSignIn(1, true)

        assertThat(store.markSignInNotified(1)).isTrue()
    }

    @Test
    fun `accounts are announced independently`() = runTest {
        store.markSignInNotified(1)

        assertThat(store.markSignInNotified(2)).isTrue()
    }
}

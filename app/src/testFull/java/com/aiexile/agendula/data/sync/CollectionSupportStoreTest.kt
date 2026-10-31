package com.aiexile.agendula.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import com.aiexile.caldav.CollectionSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The cache in front of OPTIONS, which exists so a picker can draw at once. */
class CollectionSupportStoreTest {

    @TempDir lateinit var directory: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(directory, "support.preferences_pb") }
    }

    private val store by lazy { CollectionSupportStore(dataStore) }

    @AfterEach fun tearDown() = scope.cancel()

    @Test
    fun `an account nobody has asked about offers nothing`() = runTest {
        // Unknown reads as "no": the cost of a missing affordance is one tap to
        // get it back, and the cost of the other error is a 405 at the end of a
        // form the user has already filled in.
        assertThat(store.get(1)).isEqualTo(CollectionSupport.NONE)
    }

    @Test
    fun `a refreshed answer is the one that comes back`() = runTest {
        val answered = store.refresh(1) { CollectionSupport(mkCalendar = false, extendedMkCol = true) }

        assertThat(answered.extendedMkCol).isTrue()
        assertThat(store.get(1)).isEqualTo(answered)
    }

    @Test
    fun `a server that loses the capability says so on the next ask`() = runTest {
        store.refresh(1) { CollectionSupport(mkCalendar = true, extendedMkCol = false) }
        store.refresh(1) { CollectionSupport.NONE }

        // The whole reason this is a cache rather than the answer: a config
        // change on the server has to be able to take the affordance away.
        assertThat(store.get(1).canCreate).isFalse()
    }

    @Test
    fun `one account's answer leaves another's alone`() = runTest {
        store.refresh(1) { CollectionSupport(mkCalendar = true, extendedMkCol = false) }
        store.refresh(2) { CollectionSupport(mkCalendar = false, extendedMkCol = true) }
        store.forget(1)

        assertThat(store.get(1)).isEqualTo(CollectionSupport.NONE)
        assertThat(store.get(2).extendedMkCol).isTrue()
    }
}

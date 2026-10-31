package com.aiexile.agendula.data.sync.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import com.aiexile.caldav.PushSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Instant

class PushStoreTest {

    @TempDir lateinit var directory: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(directory, "push.preferences_pb") }
    }

    private val store by lazy { PushStore(dataStore) }

    @AfterEach fun tearDown() = scope.cancel()

    @Test
    fun `support and subscription survive a round trip, separators and all`() = runTest {
        // Server-chosen strings: nothing stops a topic or a URL carrying the
        // separator the entries are joined with.
        val support = PushSupport(topic = "a|b c", vapidPublicKey = "BA1H_x-y")
        store.recordSupport(mapOf(1L to support))
        store.recordSubscription(
            listId = 1,
            subscription = "https://dav.example.com/subs/1?x=|",
            endpoint = "https://up.example.net/abc",
            expires = Instant.fromEpochSeconds(1_800_000_000),
        )

        assertThat(store.all()[1]).isEqualTo(
            PushStore.ListPush(
                listId = 1,
                support = support,
                subscription = "https://dav.example.com/subs/1?x=|",
                endpoint = "https://up.example.net/abc",
                expires = Instant.fromEpochSeconds(1_800_000_000),
            ),
        )
    }

    @Test
    fun `only a change in support is reported as one`() = runTest {
        val support = PushSupport(topic = "t", vapidPublicKey = null)

        assertThat(store.recordSupport(mapOf(1L to support))).isTrue()
        assertThat(store.recordSupport(mapOf(1L to support))).isFalse()
        // A rotated VAPID key is a change: it needs a new registration.
        assertThat(store.recordSupport(mapOf(1L to support.copy(vapidPublicKey = "new")))).isTrue()
        // Nothing known, nothing offered: no change either.
        assertThat(store.recordSupport(mapOf(2L to null))).isFalse()
    }

    @Test
    fun `losing support keeps the subscription until it is removed`() = runTest {
        store.recordSupport(mapOf(1L to PushSupport(topic = "t", vapidPublicKey = null)))
        store.recordSubscription(1, "https://dav/subs/1", "https://up/1", Instant.fromEpochSeconds(1))

        store.recordSupport(mapOf(1L to null))

        // Still here, so the registrar can find it and tell the server.
        assertThat(store.all()[1]?.subscription).isEqualTo("https://dav/subs/1")

        store.clearSubscription(1)
        assertThat(store.all()).doesNotContainKey(1L)
    }

    @Test
    fun `forgetting a list leaves the others alone`() = runTest {
        store.recordSupport(
            mapOf(
                1L to PushSupport(topic = "one", vapidPublicKey = null),
                2L to PushSupport(topic = "two", vapidPublicKey = null),
            ),
        )

        store.forget(setOf(1L))

        assertThat(store.all().keys).containsExactly(2L)
    }
}

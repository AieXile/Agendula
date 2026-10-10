package de.jeanlucmakiola.agendula.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Instant

/** "Retry" on a quarantined task: one resource released, nothing else touched. */
class QuarantineRetryTest {

    @TempDir lateinit var directory: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(directory, "sync.preferences_pb") }
    }

    private val quarantine by lazy { QuarantineStore(dataStore) }
    private val notices by lazy { SyncNoticeStore(dataStore) }

    @AfterEach fun tearDown() = scope.cancel()

    @Test
    fun `releasing a resource clears only its count`() = runTest {
        quarantine.merge(
            updates = mapOf(
                QuarantineStore.key(1, BAD) to 3,
                QuarantineStore.key(1, OTHER) to 3,
                QuarantineStore.key(9, BAD) to 3,
            ),
            cleared = emptySet(),
        )

        quarantine.release(setOf(1, 2), BAD)

        assertThat(quarantine.counts()).containsExactly(
            QuarantineStore.key(1, OTHER), 3,
            // Another account's list is not this retry's business.
            QuarantineStore.key(9, BAD), 3,
        )
    }

    @Test
    fun `a retried quarantine notice goes, a discarded edit stays`() = runTest {
        notices.record(
            accountId = 1,
            at = Instant.fromEpochMilliseconds(1),
            reports = listOf(
                SyncReport(
                    listId = 1,
                    listName = "Tasks",
                    discardedEdits = listOf(DiscardedEdit("u", "Milk", DiscardedEdit.Cause.SERVER_NEWER)),
                    quarantined = listOf(
                        QuarantinedResource(BAD, "415", QuarantineStore.THRESHOLD),
                        QuarantinedResource(OTHER, "415", QuarantineStore.THRESHOLD),
                    ),
                ),
            ),
        )

        notices.forgetQuarantined(1, BAD)

        val left = notices.observeAll().first()
        assertThat(left.map { it.key }).containsExactly("u", OTHER)
    }

    private companion object {
        const val BAD = "http://server/dav/tasks/bad.ics"
        const val OTHER = "http://server/dav/tasks/other.ics"
    }
}

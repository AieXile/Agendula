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

/**
 * The two halves of "tell the user what the sync did".
 *
 * They are stored differently on purpose — one is news, the other a standing
 * condition — and getting that backwards either loses the news to the next quiet
 * sync or re-announces the same broken task every four hours for ever.
 */
class SyncNoticeStoreTest {

    @TempDir lateinit var directory: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(directory, "notices.preferences_pb") }
    }

    private val store by lazy { SyncNoticeStore(dataStore) }

    @AfterEach fun tearDown() = scope.cancel()

    @Test
    fun `a discarded edit is kept when a later sync finds nothing`() = runTest {
        store.record(ACCOUNT, at(1), listOf(report(discarded = listOf(edit("Milk")))))
        store.record(ACCOUNT, at(2), listOf(report()))

        // ⚠️ The edit is gone from the device either way. A clean sync an hour
        // later does not make that untrue, and replacing the set would erase the
        // one thing worth saying before anyone saw it.
        val kept = store.observeAll().first()
        assertThat(kept.map { it.subject }).containsExactly("Milk")
    }

    @Test
    fun `a resource that starts syncing again clears its own notice`() = runTest {
        store.record(ACCOUNT, at(1), listOf(report(quarantined = listOf(stuck("a.ics")))))
        store.record(ACCOUNT, at(2), listOf(report()))

        // A standing condition, not news: it is re-reported for as long as it
        // holds, so the absence of a report *is* the recovery.
        assertThat(store.observeAll().first()).isEmpty()
    }

    @Test
    fun `a resource still failing is not announced twice`() = runTest {
        val first = store.record(ACCOUNT, at(1), listOf(report(quarantined = listOf(stuck("a.ics")))))
        val second = store.record(ACCOUNT, at(2), listOf(report(quarantined = listOf(stuck("a.ics")))))

        assertThat(first).hasSize(1)
        // Announcing it every four hours is how a user learns to ignore the one
        // that matters. It stays on the account screen throughout.
        assertThat(second).isEmpty()
        assertThat(store.observeAll().first()).hasSize(1)
    }

    @Test
    fun `a resource below the threshold says nothing`() = runTest {
        val fresh = store.record(
            ACCOUNT,
            at(1),
            listOf(report(quarantined = listOf(stuck("a.ics", failures = 1)))),
        )

        // Still being retried. "One of your tasks has stopped syncing" would be
        // untrue of a single 502 from a proxy mid-restart.
        assertThat(fresh).isEmpty()
        assertThat(store.observeAll().first()).isEmpty()
    }

    @Test
    fun `two untitled edits in one run are both reported`() = runTest {
        store.record(
            ACCOUNT,
            at(1),
            listOf(report(discarded = listOf(edit(null, "uid-a"), edit(null, "uid-b")))),
        )

        // ⚠️ These are stored as a Set<String>. Two discards from one run share
        // an account, a list, a cause and a timestamp, so without the UID in the
        // key they encoded identically and one silently vanished — the
        // notification counted two and the screen listed one.
        assertThat(store.observeAll().first()).hasSize(2)
    }

    @Test
    fun `two edits with the same title are both reported`() = runTest {
        store.record(
            ACCOUNT,
            at(1),
            listOf(report(discarded = listOf(edit("Milk", "uid-a"), edit("Milk", "uid-b")))),
        )

        assertThat(store.observeAll().first()).hasSize(2)
    }

    @Test
    fun `a quarantined task is named by its title where one is known`() = runTest {
        val href = "https://server/dav/tasks/a1f9c3e2.ics"
        store.record(
            ACCOUNT,
            at(1),
            listOf(report(quarantined = listOf(stuck("a1f9c3e2.ics")))),
            titles = mapOf(href to "Renew the passport"),
        )

        // "A task has stopped syncing" over a row reading `a1f9c3e2.ics` names
        // nothing the user can act on — the same objection this file makes to
        // showing a UID.
        assertThat(store.observeAll().first().single().subject).isEqualTo("Renew the passport")
    }

    @Test
    fun `a quarantined resource we never stored falls back to its filename`() = runTest {
        store.record(ACCOUNT, at(1), listOf(report(quarantined = listOf(stuck("a1f9c3e2.ics")))))

        // Nothing local to name it by, so the filename is genuinely all there is.
        assertThat(store.observeAll().first().single().subject).isEqualTo("a1f9c3e2.ics")
    }

    @Test
    fun `titles and list names survive whatever characters they contain`() = runTest {
        store.record(
            ACCOUNT,
            at(1),
            listOf(
                report(
                    listName = "Work | Home",
                    discarded = listOf(edit("Pay the bill | urgent")),
                ),
            ),
        )

        // A list is named by its owner and a task titled by its author, so both
        // can hold the separator this store writes with.
        val notice = store.observeAll().first().single()
        assertThat(notice.subject).isEqualTo("Pay the bill | urgent")
        assertThat(notice.listName).isEqualTo("Work | Home")
    }

    @Test
    fun `one account's notices leave another's alone`() = runTest {
        store.record(ACCOUNT, at(1), listOf(report(discarded = listOf(edit("Milk")))))
        store.record(OTHER, at(1), listOf(report(discarded = listOf(edit("Bread")))))
        store.dismiss(ACCOUNT)

        assertThat(store.observeAll().first().map { it.subject }).containsExactly("Bread")
    }

    private fun report(
        listName: String = "Tasks",
        discarded: List<DiscardedEdit> = emptyList(),
        quarantined: List<QuarantinedResource> = emptyList(),
    ) = SyncReport(
        listId = 1,
        listName = listName,
        discardedEdits = discarded,
        quarantined = quarantined,
    )

    private fun edit(title: String?, uid: String = "uid-$title") =
        DiscardedEdit(uid = uid, title = title, cause = DiscardedEdit.Cause.SERVER_NEWER)

    private fun stuck(href: String, failures: Int = QuarantineStore.THRESHOLD) =
        QuarantinedResource(href = "https://server/dav/tasks/$href", reason = "415", failures = failures)

    private fun at(seconds: Long) = Instant.fromEpochMilliseconds(seconds * 1_000)

    private companion object {
        const val ACCOUNT = 1L
        const val OTHER = 2L
    }
}

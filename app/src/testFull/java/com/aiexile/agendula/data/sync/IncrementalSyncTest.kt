package com.aiexile.agendula.data.sync

import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.data.tasks.room.TaskEntity
import com.aiexile.agendula.data.tasks.room.TaskListEntity
import com.aiexile.caldav.ChangeSet
import com.aiexile.caldav.ETag
import com.aiexile.caldav.RemoteRef
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** RFC 6578, from the engine's side. */
class IncrementalSyncTest {

    private val store = FakeStore()
    private val remote = FakeRemote()
    private val quarantine = mutableMapOf<String, Int>()
    private var list = TaskListEntity(
        id = 1,
        name = "Tasks",
        color = 0,
        accountId = 7,
        href = "http://server/dav/tasks/",
        syncToken = "urn:x:1",
    )

    init {
        remote.supportsSyncCollection = true
        remote.syncToken = "urn:x:from-propfind"
    }

    private fun sync(fullDue: Boolean = false) =
        CollectionSyncer(store) { NOW }.sync(list, remote, quarantine, fullDue)

    // ------------------------------------------------------------ the cursor

    @Test fun `the token is stored only after its bodies are applied`() {
        remote.put("one.ics", vtodo("a", "New"))
        remote.changePages += page(changed = listOf("one.ics"), token = "urn:x:2")

        sync()

        assertThat(store.rows.single().title).isEqualTo("New")
        assertThat(store.tokenWrites).containsExactly("urn:x:2")
        // The full path was not needed, so no listing was taken.
        assertThat(remote.log).doesNotContain("LIST")
    }

    @Test fun `a truncated page is resumed and each page commits its own token`() {
        remote.put("one.ics", vtodo("a", "First"))
        remote.put("two.ics", vtodo("b", "Second"))
        remote.changePages += page(changed = listOf("one.ics"), token = "urn:x:2", truncated = true)
        remote.changePages += page(changed = listOf("two.ics"), token = "urn:x:3")

        sync()

        assertThat(store.rows.map { it.uid }).containsExactly("a", "b")
        // ⚠️ Per page, in order. A token written ahead of its bodies is a
        // permanent hole in the collection the moment the process dies.
        assertThat(store.tokenWrites).containsExactly("urn:x:2", "urn:x:3").inOrder()
    }

    @Test fun `a server that truncates without advancing its token is not looped on`() {
        remote.changePages += page(changed = emptyList(), token = "urn:x:1", truncated = true)

        val report = sync()

        // The RFC never requires the token to advance. The full path then
        // reconciled the collection, so this is a note rather than a failure.
        assertThat(report.incrementalNote).contains("without advancing")
        assertThat(report.failure).isNull()
        assertThat(remote.log.count { it.startsWith("CHANGES") }).isEqualTo(1)
    }

    @Test fun `a page with no token at all falls back to a full reconciliation`() {
        remote.put("one.ics", vtodo("a", "New"))
        remote.changePages += page(changed = listOf("one.ics"), token = null)

        val report = sync()

        assertThat(store.rows.single().uid).isEqualTo("a")
        assertThat(report.reconciledInFull).isTrue()
        assertThat(remote.log).contains("LIST")
    }

    // ------------------------------------------------------- invalidation

    @Test fun `an invalidated token is cleared and the run reconciles in full`() {
        remote.put("one.ics", vtodo("a", "From the listing"))
        remote.changePages += ChangeSet.TokenInvalid

        val report = sync()

        // Cleared first, so a crash before the full run cannot leave behind a
        // token we already know the server rejects.
        assertThat(store.tokenWrites.first()).isNull()
        assertThat(report.reconciledInFull).isTrue()
        assertThat(store.rows.single().uid).isEqualTo("a")
        // Adopted from the PROPFIND that preceded the listing, never after it.
        assertThat(store.tokenWrites.last()).isEqualTo("urn:x:from-propfind")
    }

    @Test fun `an unsupported report falls back without clearing the token`() {
        remote.put("one.ics", vtodo("a", "From the listing"))
        remote.changePages += ChangeSet.Unsupported

        val report = sync()

        assertThat(report.reconciledInFull).isTrue()
        assertThat(store.tokenWrites).doesNotContain(null)
    }

    // ---------------------------------------------------------- membership

    @Test fun `a removal for an href we never had is a no-op`() {
        remote.changePages += page(removed = listOf("stranger.ics"), token = "urn:x:2")

        val report = sync()

        // Created and deleted between two syncs: reported as removed without ever
        // having been reported as added.
        assertThat(report.failure).isNull()
        assertThat(report.deletedLocally).isEqualTo(0)
    }

    @Test fun `a removal we do know about deletes the rows`() {
        store.rows += task(id = 1, uid = "a")
            .copy(href = "http://server/dav/tasks/one.ics", etag = "e")
        remote.changePages += page(removed = listOf("one.ics"), token = "urn:x:2")

        val report = sync()

        assertThat(store.rows).isEmpty()
        assertThat(report.deletedLocally).isEqualTo(1)
    }

    @Test fun `delete-then-recreate at the same href replaces the old task`() {
        store.rows += task(id = 1, uid = "old")
            .copy(href = "http://server/dav/tasks/one.ics", etag = "e-old")
        remote.put("one.ics", vtodo("new", "A different task"), eTag = "e-new")
        remote.changePages += page(changed = listOf("one.ics"), token = "urn:x:2")

        sync()

        // ⚠️ Reported as a *change*, not as a removal plus an addition — so the
        // UID in the body is the only thing that says the old task is gone.
        assertThat(store.rows.map { it.uid }).containsExactly("new")
    }

    @Test fun `a mass removal is refused and reconciled against a listing instead`() {
        (1..20).forEach { n ->
            store.rows += task(id = n.toLong(), uid = "u$n")
                .copy(href = "http://server/dav/tasks/$n.ics", etag = "e")
            remote.put("$n.ics", vtodo("u$n", "Task $n"))
        }
        remote.changePages += page(removed = (1..20).map { "$it.ics" }, token = "urn:x:2")

        val report = sync()

        // ⚠️ ACL churn arrives looking exactly like this. Acting on it deletes
        // the user's data on the strength of a change log.
        assertThat(report.incrementalNote).contains("removed 20 of 20")
        assertThat(report.reconciledInFull).isTrue()
        assertThat(store.rows).hasSize(20)
    }

    @Test fun `a delete-and-recreate in one page keeps the row's local columns`() {
        store.rows += task(id = 1, uid = "a", title = "Old")
            .copy(
                href = "http://server/dav/tasks/one.ics",
                etag = "e-old",
                sortOrder = 5,
                color = 0x00FF00,
            )
        remote.put("two.ics", vtodo("a", "Same task, new file"), eTag = "e-new")
        remote.changePages += page(
            changed = listOf("two.ics"),
            removed = listOf("one.ics"),
            token = "urn:x:2",
            eTag = "e-new",
        )

        val report = sync()

        // ⚠️ Same UID, new filename. Purging `one.ics` before `two.ics` is
        // fetched re-inserts the task with sortOrder 0 and no colour, and
        // reports a deletion nobody asked for.
        val row = store.rows.single()
        assertThat(row.id).isEqualTo(1)
        assertThat(row.href).isEqualTo("http://server/dav/tasks/two.ics")
        assertThat(row.sortOrder).isEqualTo(5)
        assertThat(row.color).isEqualTo(0x00FF00)
        assertThat(report.deletedLocally).isEqualTo(0)
    }

    @Test fun `an un-uploaded local edit is never downloaded over`() {
        remote.readOnly = true
        store.rows += task(id = 1, uid = "a", title = "Mine, unsent")
            .copy(href = "http://server/dav/tasks/one.ics", etag = "e-old", isDirty = true)
        remote.put("one.ics", vtodo("a", "Owner's version"), eTag = "e-new")
        remote.changePages += page(changed = listOf("one.ics"), token = "urn:x:2")

        sync()

        // The upload was refused because the share was demoted, so the row is
        // still dirty. Downloading over it destroys the edit with nothing in the
        // report to say so.
        assertThat(store.rows.single().title).isEqualTo("Mine, unsent")
        assertThat(store.rows.single().isDirty).isTrue()
    }

    @Test fun `a stale removal never deletes what this run just wrote`() {
        store.rows += task(id = 1, uid = "a", title = "Mine")
            .copy(href = "http://server/dav/tasks/one.ics", etag = "e-one.ics", isDirty = true)
        remote.put("one.ics", vtodo("a", "Server copy"))
        // The change log predates our own upload in this same run.
        remote.changePages += page(removed = listOf("one.ics"), token = "urn:x:2")

        val report = sync()

        assertThat(remote.log).contains("UPDATE one.ics if-match=e-one.ics")
        assertThat(store.rows).hasSize(1)
        assertThat(report.deletedLocally).isEqualTo(0)
    }

    @Test fun `a removal that loses a local edit says so`() {
        store.rows += task(id = 1, uid = "a", title = "Mine")
            .copy(href = "http://server/dav/tasks/gone.ics", etag = "e", isDirty = true)
        remote.onPut = { com.aiexile.caldav.PutOutcome.Rejected(400, "no") }
        remote.changePages += page(removed = listOf("gone.ics"), token = "urn:x:2")

        val report = sync()

        assertThat(store.rows).isEmpty()
        assertThat(report.discardedEdits.single().cause)
            .isEqualTo(DiscardedEdit.Cause.DELETED_ON_SERVER)
    }

    @Test fun `a fallback that succeeds is not reported as a failure`() {
        remote.put("one.ics", vtodo("a", "New"))
        remote.changePages += ChangeSet.Failed("HTTP 400")

        val report = sync()

        // The collection is reconciled and the user has nothing to act on, so
        // this must not surface as "last sync didn't finish".
        assertThat(report.failure).isNull()
        assertThat(report.incrementalNote).contains("400")
        assertThat(report.reconciledInFull).isTrue()
        assertThat(store.tokenWrites.last()).isEqualTo("urn:x:from-propfind")
    }

    @Test fun `a refetch the change log never mentions still happens`() {
        store.rows += task(id = 1, uid = "a", title = "Mine").copy(isDirty = true)
        remote.onPut = { href -> com.aiexile.caldav.PutOutcome.StoredNeedsRefetch(href) }
        remote.put("a.ics", vtodo("a", "Server's canonical form"))
        // A server need not echo our own write back to us.
        remote.changePages += page(token = "urn:x:2")

        sync()

        // Otherwise the row keeps a null ETag and never gets the canonical body.
        assertThat(store.rows.single().title).isEqualTo("Server's canonical form")
    }

    @Test fun `a failed download never advances the cursor`() {
        remote.changePages += page(changed = listOf("one.ics"), token = "urn:x:2")
        remote.fetchFailure = "connection reset"

        sync()

        // ⚠️ A cursor committed past bodies that never landed is a permanent hole
        // in the collection, invisible until the next full reconciliation.
        assertThat(store.tokenWrites).doesNotContain("urn:x:2")
    }

    @Test fun `a listed resource the server will not return is counted`() {
        remote.changePages += page(changed = listOf("ghost.ics"), token = "urn:x:2")

        val report = sync()

        // In the listing, so the sweep leaves it; never applied, so nothing else
        // would ever count it.
        assertThat(report.quarantined.single().reason).contains("listed but not returned")
    }

    // ------------------------------------------------------------- cadence

    @Test fun `a due full reconciliation ignores the token entirely`() {
        remote.put("one.ics", vtodo("a", "New"))

        val report = sync(fullDue = true)

        // The pruned-change-log failure has no signal at all, so the full path is
        // a permanent safety net rather than a fallback.
        assertThat(remote.log).doesNotContain("CHANGES urn:x:1")
        assertThat(report.reconciledInFull).isTrue()
    }

    @Test fun `a collection that does not advertise the report is never asked`() {
        remote.supportsSyncCollection = false
        remote.put("one.ics", vtodo("a", "New"))

        sync()

        assertThat(remote.log.none { it.startsWith("CHANGES") }).isTrue()
    }

    @Test fun `an unchanged etag in the change log is not downloaded`() {
        store.rows += task(id = 1, uid = "a", title = "Known")
            .copy(href = "http://server/dav/tasks/one.ics", etag = "e-one.ics")
        remote.put("one.ics", vtodo("a", "Known"))
        remote.changePages += page(changed = listOf("one.ics"), token = "urn:x:2", eTag = "e-one.ics")

        sync()

        assertThat(remote.log.none { it.startsWith("FETCH") }).isTrue()
    }

    // --------------------------------------------------------------- setup

    @Test fun `a deferred create is not overwritten by the change log`() {
        // A previous run's PUT landed but its answer was lost; the user has since
        // edited the task, so the row is dirty with no href.
        remote.put("a.ics", vtodo("a", "On the server"))
        store.rows += task(id = 1, uid = "a", title = "Edited after the lost PUT")
            .copy(isDirty = true)
        remote.changePages += page(changed = listOf("a.ics"), token = "urn:x:2")
        var failNext = true
        remote.onFetch = {
            if (failNext) {
                failNext = false
                Result.failure(IllegalStateException("timeout"))
            } else {
                null
            }
        }

        sync()

        // downloadChanged matches by href too, so a row with none is past its
        // dirty-row guard: without honouring `deferred` here, apply matches by
        // UID and the edit is gone with nothing in discardedEdits.
        val row = store.rows.single()
        assertThat(row.title).isEqualTo("Edited after the lost PUT")
        assertThat(row.isDirty).isTrue()
        assertThat(row.href).isNull()
    }

    private fun page(
        changed: List<String> = emptyList(),
        removed: List<String> = emptyList(),
        token: String?,
        truncated: Boolean = false,
        eTag: String = "e-changed",
    ) = ChangeSet.Page(
        changed = changed.map {
            RemoteRef("http://server/dav/tasks/$it".toHttpUrl(), ETag(eTag, weak = false))
        },
        removed = removed.map { "http://server/dav/tasks/$it".toHttpUrl() },
        token = token,
        truncated = truncated,
    )

    private fun task(id: Long, uid: String, title: String? = null) =
        TaskEntity(id = id, listId = list.id, uid = uid, title = title)

    private fun vtodo(uid: String, summary: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:$uid\r\n" +
            "SUMMARY:$summary\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-01T12:00:00Z")
    }
}

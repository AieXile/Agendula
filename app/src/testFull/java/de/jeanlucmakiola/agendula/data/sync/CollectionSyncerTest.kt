package de.jeanlucmakiola.agendula.data.sync

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.data.tasks.room.TaskEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TaskListEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TaskMove
import de.jeanlucmakiola.caldav.FetchFailure
import de.jeanlucmakiola.caldav.FetchResult
import de.jeanlucmakiola.caldav.PushSupport
import de.jeanlucmakiola.caldav.PutOutcome
import de.jeanlucmakiola.caldav.RemoteResource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class CollectionSyncerTest {

    private val store = FakeStore()
    private val remote = FakeRemote()
    private val quarantine = mutableMapOf<String, Int>()
    private val list = TaskListEntity(
        id = 1,
        name = "Tasks",
        color = 0,
        accountId = 7,
        href = "http://server/dav/tasks/",
    )

    private fun sync(fullDue: Boolean = true) =
        CollectionSyncer(store) { NOW }.sync(list, remote, quarantine, fullDue)

    // ------------------------------------------------------------------ push

    @Test fun `push support read from the collection reaches the report`() {
        remote.push = PushSupport(topic = "t1", vapidPublicKey = "key")

        val report = sync()

        assertThat(report.collectionRead).isTrue()
        assertThat(report.pushSupport).isEqualTo(PushSupport(topic = "t1", vapidPublicKey = "key"))
    }

    @Test fun `an unreadable collection says nothing about push`() {
        // Not "no support": a failed PROPFIND must not tear down a working
        // subscription.
        remote.push = PushSupport(topic = "t1", vapidPublicKey = null)
        remote.stateFailure = "503"

        val report = sync()

        assertThat(report.collectionRead).isFalse()
        assertThat(report.pushSupport).isNull()
    }

    // -------------------------------------------------------------- download

    @Test fun `a new remote task is downloaded and is not dirty`() {
        remote.put("one.ics", vtodo("a", "Buy milk"))

        val report = sync()

        val row = store.rows.single()
        assertThat(row.uid).isEqualTo("a")
        assertThat(row.title).isEqualTo("Buy milk")
        assertThat(row.href).isEqualTo("http://server/dav/tasks/one.ics")
        assertThat(row.etag).isEqualTo("e-one.ics")
        // ⚠️ Explicit, not defaulted. A downstream write that leaves this set
        // uploads what was just downloaded, and that is how a sync loop starts.
        assertThat(row.isDirty).isFalse()
        assertThat(report.downloaded).isEqualTo(1)
    }

    @Test fun `an unchanged etag is not downloaded again`() {
        remote.put("one.ics", vtodo("a", "Buy milk"))
        sync()
        remote.log.clear()

        sync()

        assertThat(remote.log).containsExactly("LIST")
    }

    @Test fun `a changed etag is downloaded`() {
        remote.put("one.ics", vtodo("a", "Buy milk"))
        sync()
        remote.put("one.ics", vtodo("a", "Buy oat milk"), eTag = "e-2")
        remote.log.clear()

        sync()

        assertThat(store.rows.single().title).isEqualTo("Buy oat milk")
        assertThat(remote.log).contains("FETCH one.ics")
    }

    @Test fun `a weak remote etag is never stored`() {
        remote.put("one.ics", vtodo("a", "Buy milk"))
        remote.resources.values.single().eTag =
            de.jeanlucmakiola.caldav.ETag("weak", weak = true)

        sync()

        // Storing it would make the next conditional write look conditional
        // while silently not being one.
        assertThat(store.rows.single().etag).isNull()
    }

    @Test fun `local-only columns survive a download`() {
        store.rows += task(id = 5, uid = "a", href = "http://server/dav/tasks/one.ics")
            .copy(etag = "old", sortOrder = 42, color = 0xFF00FF00.toInt())
        remote.put("one.ics", vtodo("a", "Renamed"), eTag = "new")

        sync()

        val row = store.rows.single()
        assertThat(row.title).isEqualTo("Renamed")
        // The server has no opinion about either, so taking the mapper's defaults
        // would silently reset the user's ordering and colour on every sync.
        assertThat(row.sortOrder).isEqualTo(42)
        assertThat(row.color).isEqualTo(0xFF00FF00.toInt())
        assertThat(row.id).isEqualTo(5)
    }

    @Test fun `RELATED-TO is resolved to a row id once both rows exist`() {
        // The child arrives first, so an eager resolution would lose the link.
        remote.put("child.ics", vtodo("child", "Sub", extra = "RELATED-TO;RELTYPE=PARENT:parent"))
        remote.put("parent.ics", vtodo("parent", "Top"))

        sync()

        val parent = store.rows.single { it.uid == "parent" }
        val child = store.rows.single { it.uid == "child" }
        assertThat(child.parentId).isEqualTo(parent.id)
    }

    @Test fun `an unreadable body quarantines just that resource`() {
        remote.put("bad.ics", "this is not iCalendar")
        remote.put("good.ics", vtodo("a", "Fine"))

        val report = sync()

        assertThat(store.rows.map { it.uid }).containsExactly("a")
        assertThat(report.quarantined.single().href).endsWith("bad.ics")
        assertThat(quarantine.values.single()).isEqualTo(1)
    }

    // ---------------------------------------------------------------- upload

    @Test fun `a new local task is created and its href recorded`() {
        store.rows += task(id = 1, uid = "a", title = "Write it down").copy(isDirty = true)

        val report = sync()

        assertThat(remote.resources.keys.single()).endsWith("/a.ics")
        val row = store.rows.single()
        assertThat(row.href).isEqualTo("http://server/dav/tasks/a.ics")
        assertThat(row.etag).isEqualTo("e-a.ics")
        assertThat(row.isDirty).isFalse()
        assertThat(report.uploaded).isEqualTo(1)
    }

    @Test fun `an edited task is updated with If-Match`() {
        remote.put("one.ics", vtodo("a", "Old"))
        store.rows += task(id = 1, uid = "a", title = "New")
            .copy(href = "http://server/dav/tasks/one.ics", etag = "e-one.ics", isDirty = true)

        sync()

        assertThat(remote.log).contains("UPDATE one.ics if-match=e-one.ics")
        assertThat(remote.resources.values.single().body).contains("SUMMARY:New")
    }

    @Test fun `a failed validator fetch defers the update instead of writing blind`() {
        remote.put("one.ics", vtodo("a", "Server"))
        // No local etag: what StoredNeedsRefetch, and any server that serves none,
        // leave behind.
        store.rows += task(id = 1, uid = "a", title = "Mine")
            .copy(href = "http://server/dav/tasks/one.ics", isDirty = true)
        var failNext = true
        remote.onFetch = {
            if (failNext) {
                failNext = false
                Result.failure(IllegalStateException("timeout"))
            } else {
                null
            }
        }

        val report = sync()

        // Not knowing the validator is not the same as the server having none.
        assertThat(remote.log.none { it.startsWith("UPDATE") }).isTrue()
        assertThat(remote.resources.values.single().body).contains("SUMMARY:Server")
        assertThat(report.unconditionalWrites).isEqualTo(0)
        val row = store.rows.single()
        assertThat(row.title).isEqualTo("Mine")
        assertThat(row.isDirty).isTrue()
        assertThat(quarantine).isEmpty()
        assertThat(report.quarantined.single().failures).isEqualTo(0)
        assertThat(report.quarantined.single().reason).contains("validator fetch failed")

        remote.onFetch = null
        sync()

        assertThat(remote.log).contains("UPDATE one.ics if-match=e-one.ics")
        assertThat(remote.resources.values.single().body).contains("SUMMARY:Mine")
        assertThat(store.rows.single().isDirty).isFalse()
        assertThat(quarantine).isEmpty()
    }

    @Test fun `a server with no validator to offer is still written to`() {
        remote.put("one.ics", vtodo("a", "Server"), eTag = null)
        store.rows += task(id = 1, uid = "a", title = "Mine")
            .copy(href = "http://server/dav/tasks/one.ics", isDirty = true)

        val report = sync()

        // The fetch succeeded and there was simply no validator — the case
        // unconditionalWrites exists to count. Deferring here instead would stop
        // syncing to every etag-less server.
        assertThat(remote.log).contains("UPDATE one.ics if-match=null")
        assertThat(report.unconditionalWrites).isEqualTo(1)
        assertThat(remote.resources.values.single().body).contains("SUMMARY:Mine")
    }

    @Test fun `a PUT that returns no usable etag triggers a refetch`() {
        store.rows += task(id = 1, uid = "a", title = "New").copy(isDirty = true)
        remote.onPut = { href -> PutOutcome.StoredNeedsRefetch(href) }

        sync()

        // Not an error: the resource is on the server, but without a usable
        // validator and possibly not as the bytes we sent.
        assertThat(remote.log).contains("FETCH a.ics")
        assertThat(store.rows.single().etag).isNull()
    }

    @Test fun `a rejected upload quarantines and leaves the row dirty`() {
        store.rows += task(id = 1, uid = "a", title = "Bad").copy(isDirty = true)
        remote.onPut = { PutOutcome.Rejected(415, "Unsupported Media Type") }

        val report = sync()

        assertThat(report.quarantined.single().reason).contains("415")
        // Retrying identical bytes cannot help, but the edit is not thrown away.
        assertThat(store.rows.single().isDirty).isTrue()
    }

    @Test fun `a quarantined resource is skipped once it hits the threshold`() {
        remote.put("one.ics", vtodo("a", "Server"))
        store.rows += task(id = 1, uid = "a", title = "Mine")
            .copy(href = "http://server/dav/tasks/one.ics", etag = "e-one.ics", isDirty = true)
        remote.onPut = { PutOutcome.Rejected(400, "Bad Request") }

        repeat(QuarantineStore.THRESHOLD) { sync() }
        remote.log.clear()
        sync()

        // ⚠️ A single 400 on one resource has halted all calendar sync in DAVx5
        // for weeks. After the threshold this one is left alone and the rest of
        // the collection still runs.
        assertThat(remote.log).containsExactly("LIST")
    }

    @Test fun `an invalid body never leaves the device`() {
        store.rows += task(id = 1, uid = "a", title = "Backwards").copy(
            isDirty = true,
            dtstart = Instant.parse("2026-03-01T10:00:00Z"),
            due = Instant.parse("2026-03-01T09:00:00Z"),
        )

        val report = sync()

        assertThat(remote.log).containsExactly("LIST")
        assertThat(report.quarantined.single().reason).contains("DUE precedes DTSTART")
    }

    // -------------------------------------------------------------- conflict

    @Test fun `a 412 on update discards the local edit and reports it`() {
        remote.put("one.ics", vtodo("a", "Server won"), eTag = "newer")
        store.rows += task(id = 1, uid = "a", title = "Mine")
            .copy(href = "http://server/dav/tasks/one.ics", etag = "stale", isDirty = true)

        val report = sync()

        // Decision 2: server wins. The report is the other half of that policy —
        // without it this is indistinguishable from data loss.
        assertThat(report.discardedEdits.single().cause)
            .isEqualTo(DiscardedEdit.Cause.SERVER_NEWER)
        assertThat(report.discardedEdits.single().title).isEqualTo("Mine")
        assertThat(store.rows.single().title).isEqualTo("Server won")
        assertThat(store.rows.single().isDirty).isFalse()
    }

    @Test fun `a lost 412 conflict keeps the edit when the replacement never arrives`() {
        remote.put("one.ics", vtodo("a", "Server won"), eTag = "newer")
        store.rows += task(id = 1, uid = "a", title = "Mine")
            .copy(href = "http://server/dav/tasks/one.ics", etag = "stale", isDirty = true)
        // The 412 lands, then the replacement download dies.
        remote.resources["http://server/dav/tasks/one.ics"]!!.body = "not iCalendar"

        val report = sync()

        // Nothing was replaced, so nothing was discarded — and the edit is still
        // there to try again with.
        assertThat(report.discardedEdits).isEmpty()
        assertThat(store.rows.single().title).isEqualTo("Mine")
        assertThat(store.rows.single().isDirty).isTrue()
    }

    @Test fun `an edit to a task deleted on the server loses`() {
        store.rows += task(id = 1, uid = "a", title = "Mine")
            .copy(href = "http://server/dav/tasks/gone.ics", etag = "e", isDirty = true)

        val report = sync()

        assertThat(store.rows).isEmpty()
        assertThat(report.discardedEdits.single().cause)
            .isEqualTo(DiscardedEdit.Cause.DELETED_ON_SERVER)
    }

    @Test fun `a delete that loses to a server edit is undone`() {
        remote.put("one.ics", vtodo("a", "Server changed it"), eTag = "newer")
        store.rows += task(id = 1, uid = "a", title = "Mine").copy(
            href = "http://server/dav/tasks/one.ics",
            etag = "stale",
            isDeleted = true,
            isDirty = true,
        )

        val report = sync()

        assertThat(report.discardedEdits.single().cause)
            .isEqualTo(DiscardedEdit.Cause.DELETE_LOST)
        val row = store.rows.single()
        assertThat(row.isDeleted).isFalse()
        assertThat(row.title).isEqualTo("Server changed it")
    }

    // -------------------------------------------------------------- deletion

    @Test fun `a tombstone with an href is deleted on the server`() {
        remote.put("one.ics", vtodo("a", "Gone"))
        store.rows += task(id = 1, uid = "a")
            .copy(href = "http://server/dav/tasks/one.ics", etag = "e-one.ics", isDeleted = true)

        val report = sync()

        assertThat(remote.resources).isEmpty()
        assertThat(store.rows).isEmpty()
        assertThat(report.deletedRemotely).isEqualTo(1)
    }

    @Test fun `a task deleted before it was ever uploaded is never DELETEd`() {
        store.rows += task(id = 1, uid = "a").copy(isDeleted = true, isDirty = true)

        sync()

        // A DELETE here would 404 on every sync, forever.
        assertThat(remote.log).containsExactly("LIST")
        assertThat(store.rows).isEmpty()
    }

    @Test fun `a deleted occurrence is dropped from the store once the PUT lands`() {
        remote.put("one.ics", vtodo("a", "Weekly"))
        store.rows += task(id = 1, uid = "a", title = "Weekly")
            .copy(
                href = "http://server/dav/tasks/one.ics",
                etag = "e-one.ics",
                rrule = "FREQ=WEEKLY",
            )
        store.rows += task(id = 2, uid = "a", title = "Weekly")
            .copy(
                href = "http://server/dav/tasks/one.ics",
                etag = "e-one.ics",
                masterId = 1,
                recurrenceId = NOW,
                isDeleted = true,
                isDirty = true,
            )

        sync()

        // The occurrence left the body, so the PUT is its deletion. Marking the
        // tombstone synced instead strands is_deleted = 1 with is_dirty = 0,
        // which no phase can reach and which the unique index on
        // (list_id, uid, recurrence_id) turns into a permanent failure to re-add.
        assertThat(remote.log.any { it.startsWith("UPDATE one.ics") }).isTrue()
        assertThat(remote.log.none { it.startsWith("DELETE") }).isTrue()
        assertThat(store.rows.none { it.isDeleted }).isTrue()
        assertThat(store.rows.map { it.id }).containsExactly(1L)
        // FakeStore models neither the unique index on (list_id, uid,
        // recurrence_id) nor the master_id cascade, so the permanent-failure-to-
        // re-add consequence is reasoned about, not asserted here.
        assertThat(store.rows.single().isDirty).isFalse()
    }

    @Test fun `a tombstoned master with a live override keeps its row`() {
        remote.put("one.ics", vtodo("a", "Series"))
        store.rows += task(id = 1, uid = "a", title = "Series").copy(
            href = "http://server/dav/tasks/one.ics",
            etag = "e-one.ics",
            isDeleted = true,
            isDirty = true,
        )
        store.rows += task(id = 2, uid = "a", title = "Series").copy(
            href = "http://server/dav/tasks/one.ics",
            etag = "e-one.ics",
            masterId = 1,
            recurrenceId = NOW,
        )

        sync()

        // ⚠️ The shape an older version of markDeleted left behind: the master
        // tombstoned, its overrides live. Read as rows.all it was "partly
        // deleted", so it went to the upload phase, no DELETE was ever sent, and
        // it ended is_deleted = 1, is_dirty = 0 with a matching ETag — beyond
        // every phase's reach, gone here and still there for everyone else. The
        // master's tombstone is the resource's, so the next sync finishes what
        // the user asked for.
        assertThat(remote.log).contains("DELETE one.ics if-match=e-one.ics")
        assertThat(store.rows).isEmpty()
    }

    @Test fun `the sweep does not remove what this run just created`() {
        store.rows += task(id = 1, uid = "a", title = "Fresh").copy(isDirty = true)

        sync()

        // The listing was taken before the upload, so the new href is not in it.
        assertThat(store.rows).hasSize(1)
        assertThat(remote.resources).hasSize(1)
    }

    @Test fun `an empty listing never sweeps`() {
        store.rows += task(id = 1, uid = "a", title = "Mine")
            .copy(href = "http://server/dav/tasks/one.ics", etag = "e")
        store.rows += task(id = 2, uid = "b", title = "Also mine")
            .copy(href = "http://server/dav/tasks/two.ics", etag = "e")

        val report = sync()

        // ⚠️ A server that mishandles the VTODO comp-filter answers with an empty
        // *successful* multistatus, which is indistinguishable from an empty
        // collection. Sweeping on that evidence hard-deletes every task at once.
        assertThat(store.rows).hasSize(2)
        assertThat(report.deletedLocally).isEqualTo(0)
        assertThat(report.failure).contains("listed no tasks")
    }

    @Test fun `a resource missing from a non-empty listing is swept`() {
        remote.put("one.ics", vtodo("a", "Still there"))
        store.rows += task(id = 1, uid = "a")
            .copy(href = "http://server/dav/tasks/one.ics", etag = "e-one.ics")
        store.rows += task(id = 2, uid = "b")
            .copy(href = "http://server/dav/tasks/two.ics", etag = "e")

        val report = sync()

        assertThat(store.rows.map { it.uid }).containsExactly("a")
        assertThat(report.deletedLocally).isEqualTo(1)
    }

    @Test fun `a task recreated under a new href is repointed, not swept`() {
        // Another client deleted one.ics and recreated the same UID as two.ics.
        remote.put("two.ics", vtodo("a", "Buy milk"))
        store.rows += task(id = 5, uid = "a", title = "Buy milk")
            .copy(
                href = "http://server/dav/tasks/one.ics",
                etag = "e-one.ics",
                sortOrder = 42,
                color = 0xFF0000,
            )

        val report = sync()

        // The download re-points row 5; a sweep reading the pre-download list
        // would purge it by id and lose the local-only columns for good.
        val row = store.rows.single()
        assertThat(row.id).isEqualTo(5)
        assertThat(row.href).isEqualTo("http://server/dav/tasks/two.ics")
        assertThat(row.sortOrder).isEqualTo(42)
        assertThat(row.color).isEqualTo(0xFF0000)
        assertThat(report.deletedLocally).isEqualTo(0)
        assertThat(report.downloaded).isEqualTo(1)
    }

    @Test fun `the post-download sweep still removes a stale row`() {
        remote.put("one.ics", vtodo("a", "New"))
        store.rows += task(id = 1, uid = "b")
            .copy(href = "http://server/dav/tasks/gone.ics", etag = "e")

        val report = sync()

        // Reading locals after the download widens what the sweep sees; the row
        // just inserted must survive it, the stale one must not.
        assertThat(store.rows.map { it.uid }).containsExactly("a")
        assertThat(report.deletedLocally).isEqualTo(1)
    }

    @Test fun `a resource the server refuses is counted, and the batch survives`() {
        remote.put("one.ics", vtodo("a", "Fine"))
        remote.put("two.ics", vtodo("b", "Refused"))
        remote.onFetch = { hrefs -> refuseTwoIcs(hrefs, code = 403) }

        val report = sync()

        // A refusal on one member says nothing about the others.
        assertThat(store.rows.map { it.uid }).containsExactly("a")
        assertThat(quarantine.values.single()).isEqualTo(1)
        assertThat(report.quarantined.single().failures).isEqualTo(1)
        assertThat(report.quarantined.single().reason).contains("403")
    }

    @Test fun `a resource the server says is gone is not counted`() {
        remote.put("one.ics", vtodo("a", "Fine"))
        remote.put("two.ics", vtodo("b", "Gone"))
        remote.onFetch = { hrefs -> refuseTwoIcs(hrefs, code = 404) }

        val report = sync()

        // Counting it would quarantine the resource out of the sweep that is
        // about to clean it up once the listing drops it.
        assertThat(quarantine).isEmpty()
        assertThat(report.quarantined.single().failures).isEqualTo(0)
    }

    @Test fun `a resource the server fails on is not counted`() {
        remote.put("one.ics", vtodo("a", "Fine"))
        remote.put("two.ics", vtodo("b", "Broken"))
        remote.onFetch = { hrefs -> refuseTwoIcs(hrefs, code = 500) }

        val report = sync()

        // A multiget carries no body of ours, so there is nothing here a server
        // could be permanently right to reject.
        assertThat(quarantine).isEmpty()
        assertThat(report.quarantined.single().failures).isEqualTo(0)
    }

    @Test fun `a missing href is not counted when the server also sent strays`() {
        remote.put("one.ics", vtodo("a", "Fine"))
        remote.onFetch = { hrefs ->
            Result.success(
                FetchResult(
                    resources = emptyList(),
                    missing = hrefs,
                    failed = emptyList(),
                    // The shape an href we failed to recognise takes: the same
                    // path, in both buckets.
                    unsolicited = listOf("http://elsewhere/dav/tasks/one.ics".toHttpUrl()),
                ),
            )
        }

        val report = sync()

        // ⚠️ Counting here is irreversible — at THRESHOLD the href is stripped
        // before the fetch, so nothing can ever clear it again.
        assertThat(quarantine).isEmpty()
        assertThat(report.quarantined.single().failures).isEqualTo(0)
    }

    @Test fun `a stray for another path does not excuse a missing one`() {
        remote.put("one.ics", vtodo("a", "Fine"))
        remote.onFetch = { hrefs ->
            Result.success(
                FetchResult(
                    resources = emptyList(),
                    missing = hrefs,
                    failed = emptyList(),
                    // Servers volunteer siblings routinely. Excusing every
                    // missing href on that basis would mean nothing is ever
                    // counted, and the loop never breaks.
                    unsolicited = listOf("http://server/dav/tasks/somebody-else.ics".toHttpUrl()),
                ),
            )
        }

        val report = sync()

        assertThat(quarantine.values.single()).isEqualTo(1)
        assertThat(report.quarantined.single().failures).isEqualTo(1)
    }

    @Test fun `a missing href is still counted when nothing was unmatched`() {
        remote.put("one.ics", vtodo("a", "Fine"))
        remote.onFetch = { hrefs ->
            Result.success(
                FetchResult(
                    resources = emptyList(),
                    missing = hrefs,
                    failed = emptyList(),
                    unsolicited = emptyList(),
                ),
            )
        }

        val report = sync()

        // A plain omission repeats identically every run and nothing else breaks
        // the loop, which is what the counter is for.
        assertThat(quarantine.values.single()).isEqualTo(1)
        assertThat(report.quarantined.single().failures).isEqualTo(1)
    }

    @Test fun `a resource the server keeps refusing stops being requested`() {
        remote.put("two.ics", vtodo("b", "Refused"))
        remote.onFetch = { hrefs -> refuseTwoIcs(hrefs, code = 403) }

        repeat(QuarantineStore.THRESHOLD) { sync() }
        remote.log.clear()
        sync(fullDue = false)

        // The whole point. It is in the listing, so the sweep leaves it alone,
        // and it never reaches apply, so without a count it is re-requested on
        // every sync for ever. The daily reconciliation still probes it once —
        // otherwise nothing on this side could ever clear the count.
        assertThat(remote.log).containsExactly("LIST")
    }

    @Test fun `a create the server keeps refusing is eventually quarantined`() {
        store.rows += task(id = 1, uid = "a", title = "Never acceptable").copy(isDirty = true)
        remote.onPut = { PutOutcome.Rejected(415, "Unsupported Media Type") }

        repeat(QuarantineStore.THRESHOLD) { sync() }
        remote.log.clear()
        sync()

        // A resource with no href yet still has to be countable, or it is re-PUT
        // on every sync forever.
        assertThat(remote.log).containsExactly("LIST")
        assertThat(quarantine.keys.single()).isEqualTo("${list.id}|uid:a")
    }

    @Test fun `a successful create clears the counter it accrued as a uid`() {
        store.rows += task(id = 1, uid = "a", title = "Flaky").copy(isDirty = true)
        remote.onPut = { PutOutcome.Failed("network") }
        sync()
        assertThat(quarantine).isNotEmpty()

        remote.onPut = null
        sync()

        // Both keys, or the uid-keyed one leaks into the store forever.
        assertThat(quarantine).isEmpty()
    }

    @Test fun `a RELATED-TO removed on the server unparents the task`() {
        remote.put("parent.ics", vtodo("parent", "Top"))
        remote.put("child.ics", vtodo("child", "Sub", extra = "RELATED-TO;RELTYPE=PARENT:parent"))
        sync()
        assertThat(store.rows.single { it.uid == "child" }.parentId).isNotNull()

        remote.put("child.ics", vtodo("child", "Sub"), eTag = "e-2")
        sync()

        // Carrying the old parent_id forward would re-upload, on the next local
        // edit, the relationship the user deleted elsewhere.
        assertThat(store.rows.single { it.uid == "child" }.parentId).isNull()
    }

    // ---------------------------------------------------------- name clashes

    @Test fun `a name taken by another task gets a fresh one`() {
        remote.put("a.ics", vtodo("somebody-else", "Not ours"))
        store.rows += task(id = 1, uid = "a", title = "Ours").copy(isDirty = true)

        sync()

        val ours = store.rows.single { it.uid == "a" }
        assertThat(ours.href).isNotEqualTo("http://server/dav/tasks/a.ics")
        assertThat(ours.href).endsWith(".ics")
        assertThat(ours.isDirty).isFalse()
    }

    @Test fun `a name taken by the same task is adopted`() {
        // A previous run's PUT whose answer we never saw.
        remote.put("a.ics", vtodo("a", "Uploaded last time"))
        store.rows += task(id = 1, uid = "a", title = "Ours").copy(isDirty = true)

        sync()

        assertThat(remote.resources).hasSize(1)
        assertThat(store.rows.single().href).isEqualTo("http://server/dav/tasks/a.ics")
    }

    @Test fun `a create whose verification fetch fails is not duplicated`() {
        // Our own PUT from a run whose answer never arrived, and an edit the user
        // made afterwards that only exists here.
        remote.put("a.ics", vtodo("a", "Uploaded last time"))
        store.rows += task(id = 1, uid = "a", title = "Edited after the lost PUT")
            .copy(isDirty = true)
        // Only the verification fetch fails. A blanket failure would take the
        // download phase's multiget with it and pass for the wrong reason.
        var failNext = true
        remote.onFetch = {
            if (failNext) {
                failNext = false
                Result.failure(IllegalStateException("timeout"))
            } else {
                null
            }
        }

        val report = sync()

        // Renaming on a failed fetch would put one UID in two resources, which
        // RFC 4791 §4.1 forbids and which no later sync can clean up.
        assertThat(remote.resources).hasSize(1)
        assertThat(remote.log.count { it.startsWith("CREATE") }).isEqualTo(1)
        // Deferring the write is only worth anything if the read defers too: the
        // row has no href, so downloadPhase cannot match it and would otherwise
        // overwrite the edit by UID with nothing in discardedEdits.
        val row = store.rows.single()
        assertThat(row.title).isEqualTo("Edited after the lost PUT")
        assertThat(row.isDirty).isTrue()
        assertThat(row.href).isNull()
        // Deferred, not charged: nothing can refund a UID-keyed count once the
        // resource is quarantined out of uploadPhase.
        assertThat(quarantine).isEmpty()
        assertThat(report.quarantined.single().reason).contains("verification failed")
        assertThat(report.quarantined.single().failures).isEqualTo(0)

        // And the next run adopts the resource.
        remote.onFetch = null
        sync()

        assertThat(remote.resources).hasSize(1)
        val adopted = store.rows.single()
        assertThat(adopted.href).isEqualTo("http://server/dav/tasks/a.ics")
        assertThat(adopted.title).isEqualTo("Edited after the lost PUT")
        assertThat(quarantine).isEmpty()
    }

    @Test fun `an adoption whose validator fetch fails keeps the local edit`() {
        // Create 412s, the first fetch proves the resource is ours, and the
        // validator fetch inside the adoption then fails.
        remote.put("a.ics", vtodo("a", "Uploaded last time"))
        store.rows += task(id = 1, uid = "a", title = "Edited after the lost PUT")
            .copy(isDirty = true)
        var fetches = 0
        remote.onFetch = {
            fetches += 1
            if (fetches == 2) Result.failure(IllegalStateException("timeout")) else null
        }

        sync()

        // The row still has no href here, so downloadPhase cannot match it and
        // only `deferred` keeps apply from overwriting the edit by UID. This is
        // the call site that makes updateResource's `deferred` load-bearing.
        assertThat(remote.resources).hasSize(1)
        val row = store.rows.single()
        assertThat(row.title).isEqualTo("Edited after the lost PUT")
        assertThat(row.isDirty).isTrue()
        assertThat(row.href).isNull()
    }

    @Test fun `a refused verification fetch does not rename the create`() {
        remote.put("a.ics", vtodo("a", "Uploaded last time"))
        store.rows += task(id = 1, uid = "a", title = "Edited after the lost PUT")
            .copy(isDirty = true)
        // A per-resource refusal is a *successful* multiget. Without inspecting
        // `failed`, the empty resources list reads as somebody else's resource.
        remote.onFetch = { hrefs ->
            Result.success(
                FetchResult(
                    resources = emptyList(),
                    missing = emptyList(),
                    failed = hrefs.map { FetchFailure(it, 403) },
                    unsolicited = emptyList(),
                ),
            )
        }

        sync()

        assertThat(remote.resources).hasSize(1)
        assertThat(remote.log.count { it.startsWith("CREATE") }).isEqualTo(1)
        val row = store.rows.single()
        assertThat(row.title).isEqualTo("Edited after the lost PUT")
        assertThat(row.isDirty).isTrue()
    }

    @Test fun `a refused validator fetch does not write unconditionally`() {
        remote.put("one.ics", vtodo("a", "Server"))
        store.rows += task(id = 1, uid = "a", title = "Mine")
            .copy(href = "http://server/dav/tasks/one.ics", isDirty = true)
        remote.onFetch = { hrefs ->
            Result.success(
                FetchResult(
                    resources = emptyList(),
                    missing = emptyList(),
                    failed = hrefs.map { FetchFailure(it, 500) },
                    unsolicited = emptyList(),
                ),
            )
        }

        val report = sync()

        assertThat(remote.log.none { it.startsWith("UPDATE") }).isTrue()
        assertThat(report.unconditionalWrites).isEqualTo(0)
        assertThat(remote.resources.values.single().body).contains("SUMMARY:Server")
        assertThat(store.rows.single().isDirty).isTrue()
    }

    // ------------------------------------------------------------ protection

    @Test fun `a read-only collection is never written to`() {
        remote.readOnly = true
        store.rows += task(id = 1, uid = "a", title = "Mine").copy(isDirty = true)

        val report = sync()

        assertThat(remote.log).containsExactly("LIST")
        assertThat(report.quarantined.single().reason).contains("read-only")
        // ⚠️ ACL churn is silent, so the refreshed flag is written back.
        assertThat(store.readOnlyWrites.single()).isEqualTo(list.id to true)
    }

    @Test fun `a confidential task in a shared collection is not written back`() {
        remote.shared = true
        remote.put("one.ics", vtodo("a", "Secret"))
        store.rows += task(id = 1, uid = "a", title = "Secret").copy(
            href = "http://server/dav/tasks/one.ics",
            etag = "e-one.ics",
            classification = 2,
            isDirty = true,
        )

        val report = sync()

        // ⚠️ Nextcloud's CalendarObject::get() serves a whitelist-reduced copy of
        // a confidential object from a share while leaving the ETag untouched.
        // Writing that back destroys the owner's task, and the ETag matches.
        assertThat(remote.log).containsExactly("LIST")
        assertThat(report.quarantined.single().reason).contains("reduced copy")
    }

    @Test fun `a confidential task the user owns is written normally`() {
        remote.shared = false
        remote.put("one.ics", vtodo("a", "Secret"))
        store.rows += task(id = 1, uid = "a", title = "Secret").copy(
            href = "http://server/dav/tasks/one.ics",
            etag = "e-one.ics",
            classification = 2,
            isDirty = true,
        )

        sync()

        assertThat(remote.log).contains("UPDATE one.ics if-match=e-one.ics")
    }

    // -------------------------------------------------------------- failures

    @Test fun `a collection that cannot be listed reports rather than throws`() {
        remote.listFailure = "boom"
        val report = sync()
        assertThat(report.failure).contains("boom")
    }

    @Test fun `a collection that is no longer readable reports rather than throws`() {
        remote.stateFailure = "revoked"
        val report = sync()
        assertThat(report.failure).contains("revoked")
    }

    @Test fun `a rename into a name another task took keeps both rows`() {
        store.rows += task(id = 1, uid = "a", title = "Moved")
            .copy(href = "http://server/dav/tasks/one.ics", etag = "e-old")
        // Fetched in insertion order, so the rename is applied first and leaves
        // the index entry for `one.ics` behind.
        remote.put("two.ics", vtodo("a", "Moved"))
        remote.put("one.ics", vtodo("b", "A different task at the old name"))

        sync()

        // ⚠️ `one.ics` now holds somebody else, and row 1 no longer lives there
        // — reading it out of a stale index entry deletes the task the rename
        // just repaired.
        assertThat(store.rows.map { it.uid }).containsExactly("a", "b")
    }

    @Test fun `a full reconciliation probes a resource the download gave up on`() {
        remote.put("one.ics", vtodo("a", "Buy milk"))
        quarantine[QuarantineStore.key(list.id, "http://server/dav/tasks/one.ics")] =
            QuarantineStore.THRESHOLD

        sync()

        // ⚠️ The download side has no refund — at THRESHOLD the href is stripped
        // before the fetch, so nothing can ever clear the count again. One probe
        // per periodic reconciliation is the expiry.
        assertThat(store.rows.single().uid).isEqualTo("a")
        assertThat(quarantine).isEmpty()
    }

    // ----------------------------------------------------------------- setup

    /** A multiget in which `two.ics` alone comes back refused. */
    private fun refuseTwoIcs(hrefs: List<HttpUrl>, code: Int): Result<FetchResult> {
        val refused = hrefs.filter { it.pathSegments.last() == "two.ics" }
        val rest = hrefs - refused.toSet()
        val served = remote.resources.filterKeys { key -> rest.any { it.toString() == key } }
        return Result.success(
            FetchResult(
                resources = served.map { (href, stored) ->
                    RemoteResource(
                        href = href.toHttpUrl(),
                        eTag = stored.eTag,
                        iCalendar = stored.body,
                    )
                },
                missing = emptyList(),
                failed = refused.map { FetchFailure(it, code) },
                unsolicited = emptyList(),
            ),
        )
    }

    // ------------------------------------------------------------------ move

    @Test fun `a task moved to another collection leaves the old one and is created in the new`() {
        remote.put("one.ics", vtodo("a", "Milk"))
        sync()
        val row = store.rows.single()
        val other = TaskListEntity(id = 2, name = "Other", color = 0, accountId = 7, href = "http://server/dav/other/")
        val otherRemote = FakeRemote("http://server/dav/other/".toHttpUrl())

        val plan = TaskMove.plan(row.copy(listId = other.id), row, emptyList(), true, null)
        plan.update.forEach(store::update)
        plan.tombstone?.let(store::insert)

        val syncer = CollectionSyncer(store) { NOW }
        syncer.sync(list, remote, quarantine)
        syncer.sync(other, otherRemote, quarantine)

        // ⚠️ Keeping the href would have PUT the edit back into the old
        // collection, and the new one would never have heard of it.
        assertThat(remote.log).contains("DELETE one.ics if-match=e-one.ics")
        assertThat(remote.resources).isEmpty()
        assertThat(otherRemote.resources).hasSize(1)
        val moved = store.rows.single()
        assertThat(moved.id).isEqualTo(row.id)
        assertThat(moved.listId).isEqualTo(other.id)
        assertThat(moved.href).startsWith("http://server/dav/other/")
        assertThat(moved.isDirty).isFalse()
    }

    @Test fun `a move that lost to a server edit brings the server's copy back`() {
        remote.put("one.ics", vtodo("a", "Milk"))
        sync()
        val row = store.rows.single()
        val plan = TaskMove.plan(row.copy(listId = 2), row, emptyList(), true, null)
        plan.update.forEach(store::update)
        plan.tombstone?.let(store::insert)
        remote.put("one.ics", vtodo("a", "Oat milk"), eTag = "e-2")

        val report = sync()

        // The DELETE is conditional, so a newer copy on the server survives and
        // is downloaded again rather than destroyed.
        assertThat(remote.resources).hasSize(1)
        assertThat(store.rowsIn(list.id).single().title).isEqualTo("Oat milk")
        assertThat(report.discardedEdits.single().cause).isEqualTo(DiscardedEdit.Cause.DELETE_LOST)
    }

    private fun task(
        id: Long,
        uid: String,
        title: String? = null,
        href: String? = null,
    ) = TaskEntity(id = id, listId = list.id, uid = uid, title = title, href = href)

    private fun vtodo(uid: String, summary: String, extra: String? = null) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\n")
        append("UID:$uid\r\nSUMMARY:$summary\r\n")
        extra?.let { append("$it\r\n") }
        append("END:VTODO\r\nEND:VCALENDAR\r\n")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-01T12:00:00Z")
    }
}

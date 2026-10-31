package com.aiexile.agendula.data.sync

import com.aiexile.agendula.data.tasks.ical.AlarmCodec
import com.aiexile.agendula.data.tasks.ical.CalendarResource
import com.aiexile.agendula.data.tasks.ical.ResourceValidator
import com.aiexile.agendula.data.tasks.ical.VTodoMapper
import com.aiexile.agendula.data.tasks.room.TaskAlarmEntity
import com.aiexile.agendula.data.tasks.room.TaskEntity
import com.aiexile.agendula.data.tasks.room.TaskListEntity
import at.bitfire.dav4jvm.exception.UnauthorizedException
import com.aiexile.caldav.ChangeSet
import com.aiexile.caldav.DeleteOutcome
import com.aiexile.caldav.PutOutcome
import com.aiexile.caldav.RemoteCalendar
import com.aiexile.caldav.RemoteRef
import com.aiexile.caldav.ResourceNames
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Reconciles one task list against one remote collection.
 *
 * The order is deliberate and is the whole design:
 *
 * 1. **Deletions**, so a tombstone never races the download of the resource it
 *    is about to remove.
 * 2. **Uploads**, so local work reaches the server before anything can overwrite
 *    it, and so a conflict is discovered while the local edit still exists.
 * 3. **Downloads**, including everything the write phase decided we now need a
 *    fresh copy of.
 * 4. **Sweep**, which is the only step that may delete a row it did not see fail
 *    — and so it runs last, on a listing taken before any of the writes. Its
 *    local side, unlike that listing, is read *after* the download.
 *
 * ⚠️ **A failed resource must not fail the collection**, the twin of "a failed
 * collection must not fail the account". Every per-resource outcome below either
 * counts against [QuarantineStore.THRESHOLD] or is recorded in the report; none
 * of them abandon the run.
 */
class CollectionSyncer(
    private val store: SyncStore,
    private val now: () -> Instant = { Clock.System.now() },
) {

    /**
     * @param quarantine failure counts keyed by [QuarantineStore.key], mutated in
     *   place so the caller can persist one map for the whole account.
     */
    fun sync(
        list: TaskListEntity,
        remote: RemoteCalendar,
        quarantine: MutableMap<String, Int>,
        fullReconciliationDue: Boolean = true,
    ): SyncReport {
        val run = Run(list, remote, quarantine, fullReconciliationDue)
        return try {
            run.execute()
        } catch (e: Exception) {
            // The collection is lost, the account is not.
            run.report.copy(failure = e.toString())
        }
    }

    /** One collection's pass. Mutable state lives here rather than in the class. */
    private inner class Run(
        val list: TaskListEntity,
        val remote: RemoteCalendar,
        val quarantine: MutableMap<String, Int>,
        val fullReconciliationDue: Boolean,
    ) {
        var report = SyncReport(listId = list.id, listName = list.name)

        /** Hrefs the write phase wants a fresh copy of. */
        val refetch = mutableSetOf<String>()

        /**
         * Edits that will be discarded **once the replacement actually arrives**.
         *
         * ⚠️ Reported from [apply], not from the write phase. Announcing the
         * discard at the moment of the 412 would claim a loss that has not
         * happened yet — and clearing `is_dirty` there would make it happen for
         * real if the download then failed, with nothing left to retry.
         */
        val pendingDiscard = mutableMapOf<String, DiscardedEdit.Cause>()

        /** Hrefs this run wrote, and which the sweep must therefore not remove. */
        val touched = mutableSetOf<String>()

        /**
         * Hrefs the write phase gave up on this run, and which must therefore
         * not be downloaded.
         *
         * ⚠️ A resource deferred mid-create still has `href == null` locally, so
         * [downloadPhase]'s `byHref` lookup cannot see it and its dirty-row guard
         * cannot fire. Without this the same run downloads the server's copy,
         * [apply] matches it by UID and overwrites the local edit — silently,
         * with an empty `discardedEdits` and no failure. Deferring the write only
         * helps if the read defers with it.
         */
        val deferred = mutableSetOf<String>()

        /**
         * `uid -> parent uid`, resolved to row ids once every row exists.
         *
         * The value is nullable and every downloaded resource records one: a
         * *removed* `RELATED-TO` must clear the link, and a map that only holds
         * present parents leaves the stale `parent_id` in place — which
         * [parentUidOf] then resolves back into a `RELATED-TO` and re-uploads.
         */
        val parents = mutableMapOf<String, String?>()

        var writable = true
        var shared = false

        /**
         * The token seen in the PROPFIND at the start of this run.
         *
         * Captured **before** anything is read, and adopted only after a full
         * reconciliation completes. Taking it afterwards would silently swallow
         * every change made while we were reading; taking it before merely
         * re-reports those next time, which costs one ETag comparison.
         */
        var pendingToken: String? = null

        fun execute(): SyncReport {
            val state = remote.state().getOrElse {
                return report.copy(
                    failure = "collection unavailable: $it",
                    authFailure = it is UnauthorizedException,
                )
            }
            report = report.copy(collectionRead = true, pushSupport = state.collection.push)
            writable = !state.collection.readOnly
            shared = state.collection.isShared
            persistCollectionState(state.collection.readOnly)
            pendingToken = state.syncToken

            // Local work first, in both paths. A tombstone must never race the
            // download of the resource it is about to remove, and a conflict has
            // to be discovered while the local edit still exists.
            val pending = localResources()
            deletePhase(pending)
            uploadPhase(pending)

            val cursor = list.syncToken?.takeIf {
                // ⚠️ A hint, not a contract — Radicale advertised the report for
                // years without implementing it.
                state.collection.supportsSyncCollection && !fullReconciliationDue
            }
            if (cursor == null || !runIncremental(cursor)) {
                // ⚠️ Whatever the incremental attempt recorded is a note about a
                // path we did not end up using. Leaving it as *the collection's*
                // failure refuses the new token, skips the cadence record and
                // shows the user "last sync didn't finish" — on a run that
                // reconciled the collection perfectly.
                val attempt = report.failure
                report = report.copy(failure = null)
                runFull()
                if (report.failure == null && attempt != null) {
                    report = report.copy(incrementalNote = attempt)
                }
            } else {
                // The write phase asked for fresh copies the change log may never
                // mention — a server does not have to echo our own writes back to
                // us — so they are fetched explicitly rather than hoped for.
                fetchAndApply(refetch.filterNot { isQuarantined(it) || it in deferred })
            }

            resolveParents()
            return report
        }

        /**
         * The full path: a complete listing, an ETag diff, and a sweep.
         *
         * ⚠️ Not a fallback — a **permanent safety net**. A token the server
         * accepts over a change log it has already pruned answers 207 with zero
         * changes and no error, and RFC 6578 offers no way to detect it. Running
         * this on a slow cadence regardless of the token is the only mitigation
         * there is.
         */
        private fun runFull() {
            val refs = remote.list().getOrElse {
                report = report.copy(
                    failure = "listing failed: $it",
                    authFailure = it is UnauthorizedException,
                )
                return
            }
            // ⚠️ Only strong tags are carried forward. A weak one cannot serve
            // as `If-Match`, so recording it would make every later write look
            // conditional while silently not being one.
            val remoteETags: Map<String, String?> = refs.associate { ref ->
                ref.href.toString() to ref.eTag?.takeIf { it.usable }?.value
            }

            downloadPhase(localResources(), remoteETags)
            // ⚠️ Re-read. The download just ran and may have re-pointed a row's
            // href; the pre-download list still names the vacated one.
            sweepPhase(localResources(), remoteETags.keys)

            report = report.copy(reconciledInFull = true)
            // ⚠️ Adopted only now, and taken from the PROPFIND that *preceded*
            // the listing. A token minted after the read would silently swallow
            // anything that changed during it; one minted before merely re-reports
            // it next time, and a re-report costs an ETag comparison.
            if (report.failure == null) store.setSyncToken(list.id, pendingToken)
        }

        /**
         * The incremental path.
         *
         * @return true when the collection is fully reconciled by change log
         *   alone; false to fall back to [runFull] this run.
         */
        private fun runIncremental(token: String): Boolean {
            var cursor = token
            repeat(MAX_SYNC_PAGES) {
                val page = when (val changes = remote.changes(cursor)) {
                    is ChangeSet.Page -> changes

                    ChangeSet.TokenInvalid -> {
                        // Clear it, so a crash before the full run below does not
                        // leave a token we already know the server rejects.
                        store.setSyncToken(list.id, null)
                        return false
                    }

                    ChangeSet.Unsupported -> return false

                    is ChangeSet.Failed -> {
                        report = report.copy(failure = "sync-collection failed: ${changes.reason}")
                        return false
                    }
                }

                if (!removalsArePlausible(page.removed)) return false

                // ⚠️ Bodies before removals, unlike the phase order above. A
                // delete-and-recreate arrives as `removed: one.ics` +
                // `changed: two.ics` in one page, and purging first destroys the
                // row the download would have re-pointed — it comes back with
                // `sortOrder = 0`, no colour and no parent, and `deletedLocally`
                // is reported for a task nobody deleted. Downloading first lets
                // `apply` move the row, after which `one.ics` names nothing and
                // the removal is the no-op it should be. Nothing is lost the
                // other way: RFC 6578 reports each resource once, so a page
                // cannot both change and remove the same href, and a href this
                // run wrote is already guarded by `touched`.
                downloadChanged(page.changed)
                applyRemovals(page.removed)

                // ⚠️ After the bodies, and only if they actually landed. A
                // network drop mid-multiget would otherwise commit a cursor past
                // changes that were never downloaded — a permanent hole in the
                // collection, invisible until the next full reconciliation a day
                // later. `runFull` has always had this guard; this path did not.
                if (report.failure != null) return false
                store.setSyncToken(list.id, page.token)

                val next = page.token
                when {
                    // A 207 with no token at all. The changes were real; the
                    // cursor is gone, so the next read has to be a full one.
                    next == null -> return false

                    !page.truncated -> return true

                    // ⚠️ The RFC never requires the token to advance, so a server
                    // that returns the same one forever would loop until the cap.
                    next == cursor -> {
                        report = report.copy(
                            failure = "the server truncated without advancing its sync token",
                        )
                        return false
                    }

                    else -> cursor = next
                }
            }
            report = report.copy(failure = "sync-collection did not finish in $MAX_SYNC_PAGES pages")
            return false
        }

        /**
         * ⚠️ ACL churn can arrive as a mass removal.
         *
         * A calendar whose share is revoked, or whose permissions change, can be
         * reported as every resource in it disappearing at once. Acting on that
         * deletes the user's data on the strength of a change log; reconciling
         * against a real listing instead costs one PROPFIND.
         */
        private fun removalsArePlausible(removed: List<HttpUrl>): Boolean {
            if (removed.size < MIN_REMOVALS_TO_QUESTION) return true
            val known = store.rowsIn(list.id).mapNotNull { it.href }.distinct().size
            if (known == 0 || removed.size * 2 <= known) return true
            report = report.copy(
                failure = "the change log removed ${removed.size} of $known resources at once — " +
                    "reconciled against a full listing instead",
            )
            return false
        }

        private fun applyRemovals(removed: List<HttpUrl>) {
            if (removed.isEmpty()) return
            val byHref = localResources().filter { it.href != null }.associateBy { it.href!! }
            removed.forEach { url ->
                val href = url.toString()
                // ⚠️ An unknown href is a no-op, not an error. A resource created
                // and deleted between two syncs is reported as removed without
                // ever having been reported as added.
                val local = byHref[href] ?: return@forEach
                // ⚠️ The change log describes the past. If this run has already
                // written to that href, the entry predates our write and acting on
                // it deletes the row we just uploaded — leaving an orphan on the
                // server and nothing here.
                if (href in touched) return@forEach

                if (local.isDirty) discard(local, DiscardedEdit.Cause.DELETED_ON_SERVER)
                purge(local)
                report = report.copy(deletedLocally = report.deletedLocally + 1)
            }
        }

        private fun downloadChanged(changed: List<RemoteRef>) {
            if (changed.isEmpty()) return
            val byHref = localResources().filter { it.href != null }.associateBy { it.href!! }
            val wanted = changed.filterNot { ref ->
                val href = ref.href.toString()
                // The write phase gave up on it this run. Checked before the
                // `byHref` lookup, because the case it exists for is a row whose
                // href is still null — invisible to that map, and so past the
                // dirty-row guard below.
                if (href in deferred) return@filterNot true
                val local = byHref[href] ?: return@filterNot false
                // ⚠️ A row still dirty after the write phase is an edit that never
                // reached the server — the collection was demoted to read-only, or
                // the upload was refused. Downloading over it destroys the user's
                // work silently, with nothing in the report.
                //
                // Except when the write phase *asked* for the fresh copy: that is
                // the 412 path, where the row is deliberately left dirty until its
                // replacement lands.
                if ((local.isDirty || local.isDeleted) && href !in refetch) return@filterNot true
                val eTag = ref.eTag?.takeIf { it.usable }?.value
                // Unchanged only when both sides have a strong tag and they agree.
                local.eTag != null && eTag != null && local.eTag == eTag
            }.map { it.href.toString() }.filterNot { isQuarantined(it) }

            fetchAndApply(wanted)
        }

        // ------------------------------------------------------------ phase 1

        private fun deletePhase(locals: List<LocalResource>) {
            locals.filter { it.isDeleted }.forEach { local ->
                val href = local.href
                if (href == null) {
                    // ⚠️ Never uploaded, so there is nothing to DELETE. Sending
                    // one would 404 on every sync forever.
                    purge(local)
                    return@forEach
                }
                if (isQuarantined(local.key)) return@forEach

                when (val outcome = remote.delete(url(href), local.eTag)) {
                    DeleteOutcome.Deleted -> {
                        purge(local)
                        touched += href
                        report = report.copy(deletedRemotely = report.deletedRemotely + 1)
                    }

                    DeleteOutcome.ServerNewer -> {
                        // The delete lost. Undo the tombstone and take the
                        // server's copy — decision 2, applied to a deletion.
                        clearTombstone(local)
                        refetch += href
                        touched += href
                        discard(local, DiscardedEdit.Cause.DELETE_LOST)
                    }

                    is DeleteOutcome.Rejected ->
                        fail(href, "DELETE refused: ${outcome.code} ${outcome.message}")

                    is DeleteOutcome.Failed ->
                        fail(href, "DELETE failed: ${outcome.reason}")
                }
            }
        }

        // ------------------------------------------------------------ phase 2

        private fun uploadPhase(locals: List<LocalResource>) {
            locals.filter { it.isDirty && !it.isDeleted }.forEach { local ->
                val href = local.href
                // ⚠️ Keyed by UID when there is no href yet. A create that the
                // server permanently rejects has nothing else to key on, and a
                // counter nothing ever reads is a resource re-PUT on every sync
                // forever — the exact failure quarantine exists to prevent.
                if (isQuarantined(local.key)) return@forEach

                if (!writable) {
                    skip(local.key, "the collection is read-only")
                    return@forEach
                }

                // ⚠️ The Nextcloud shared-calendar landmine. `CalendarObject::get()`
                // reduces a CLASS:CONFIDENTIAL object from a share to a
                // VEVENT-shaped whitelist — deleting DUE, STATUS, COMPLETED,
                // PERCENT-COMPLETE, PRIORITY and RELATED-TO — while leaving the
                // ETag untouched. Writing that back destroys the owner's task,
                // and the ETag matches, so nothing stops it but this.
                if (shared && href != null && local.master.classification == CLASS_CONFIDENTIAL) {
                    skip(href, "confidential task in a shared collection: the server may have served a reduced copy")
                    return@forEach
                }

                val body = serialize(local) ?: return@forEach
                if (href == null) createResource(local, body) else updateResource(local, href, body)
            }
        }

        /** Null when the resource was rejected before it left the device. */
        private fun serialize(local: LocalResource): String? {
            val alarms = store.alarmsFor(local.live.map { it.id })
            val todos = local.live.map { row ->
                VTodoMapper.write(row, parentUidOf(row), now(), alarms[row.id].orEmpty())
            }
            val calendar = CalendarResource.build(todos)

            ResourceValidator.validate(calendar)?.let { rejection ->
                // Retrying cannot help: the same bytes produce the same 415.
                fail(local.key, "not uploadable — it ${rejection.reason}")
                return null
            }
            return CalendarResource.serialize(todos)
        }

        private fun createResource(local: LocalResource, body: String) {
            var name = ResourceNames.forUid(local.uid)
            repeat(CREATE_ATTEMPTS) {
                when (val outcome = remote.create(name, body)) {
                    is PutOutcome.Stored -> {
                        stored(local, outcome.href, outcome.eTag.value)
                        return
                    }

                    is PutOutcome.StoredNeedsRefetch -> {
                        stored(local, outcome.href, eTag = null)
                        refetch += outcome.href.toString()
                        return
                    }

                    is PutOutcome.NameTaken -> {
                        // Either a previous run's PUT whose answer we never saw,
                        // or an unrelated resource squatting the name. Only the
                        // UID in the body can tell them apart.
                        //
                        // ⚠️ A fetch that *failed* is not evidence of a different
                        // task. This 412 is most often our own PUT from a run
                        // whose answer never arrived, so taking a fresh name on a
                        // timeout writes one UID to a second resource — forbidden
                        // by RFC 4791 §4.1, duplicated in every client, and a row
                        // whose href flips between the two on every later sync.
                        // Stay dirty and try again next run. A fetch that
                        // *succeeded* and returned nothing, or something else, is
                        // a name we may safely walk away from.
                        val fetched = remote.fetch(listOf(outcome.href)).getOrElse {
                            // `skip`, not `fail`: a transport failure is nobody's
                            // fault and the condition is re-evaluated next run.
                            // Counting it would spend a THRESHOLD budget that,
                            // for a resource with no href yet, nothing can ever
                            // refund — `uploadPhase` returns early once it is
                            // quarantined, so neither `stored` nor `purge` can
                            // reach it to clear the count again.
                            deferred += outcome.href.toString()
                            skip(local.key, "create verification failed: $it")
                            return
                        }
                        // A refusal is not an answer either. It arrives as a
                        // *successful* multiget carrying a per-resource error, so
                        // without this the empty `resources` reads as "somebody
                        // else's" and we take a fresh name — the duplicate UID
                        // this branch exists to avoid.
                        if (fetched.failed.isNotEmpty()) {
                            deferred += outcome.href.toString()
                            val code = fetched.failed.first().code
                            skip(local.key, "create verification refused: $code")
                            return
                        }
                        val existing = fetched.resources.firstOrNull()
                        val sameTask = existing != null && uidOf(existing.iCalendar) == local.uid
                        if (sameTask) {
                            updateResource(local, outcome.href.toString(), body)
                            return
                        }
                        name = ResourceNames.random()
                    }

                    is PutOutcome.Rejected -> {
                        fail(local.key, "create refused: ${outcome.code} ${outcome.message}")
                        return
                    }

                    is PutOutcome.Failed -> {
                        fail(local.key, "create failed: ${outcome.reason}")
                        return
                    }

                    // Unreachable on create; If-None-Match cannot produce them.
                    PutOutcome.ServerNewer, PutOutcome.Vanished -> return
                }
            }
            fail(local.key, "could not find a free name after $CREATE_ATTEMPTS attempts")
        }

        private fun updateResource(local: LocalResource, href: String, body: String) {
            val url = url(href)
            var eTag = local.eTag
            if (eTag == null) {
                // No usable validator on record. Ask for one before writing,
                // rather than writing blind.
                //
                // ⚠️ A fetch that *failed* says we do not know, not that the
                // server has none to offer. Falling through would PUT
                // unconditionally over whatever a concurrent edit had left
                // there, and book it under `unconditionalWrites` — whose whole
                // meaning is that the server offered no validator. A success
                // that yields nothing usable does mean that, and still writes.
                //
                // `deferred` is redundant on the direct call, where the row
                // keeps its href and the dirty-row guards can see it. It is
                // load-bearing on the delegated one from `createResource`, where
                // the row's href is still null and only this suppresses a
                // download that would overwrite the edit by UID.
                val fetched = remote.fetch(listOf(url)).getOrElse {
                    deferred += href
                    skip(local.key, "update validator fetch failed: $it")
                    return
                }
                // Same distinction: refused is not "has none to offer".
                if (fetched.failed.isNotEmpty()) {
                    deferred += href
                    skip(local.key, "validator fetch refused: ${fetched.failed.first().code}")
                    return
                }
                eTag = fetched.resources.firstOrNull()
                    ?.eTag?.takeIf { it.usable }?.value
                if (eTag == null) {
                    report = report.copy(unconditionalWrites = report.unconditionalWrites + 1)
                }
            }

            when (val outcome = remote.update(url, eTag, body)) {
                is PutOutcome.Stored -> stored(local, outcome.href, outcome.eTag.value)

                is PutOutcome.StoredNeedsRefetch -> {
                    stored(local, outcome.href, eTag = null)
                    refetch += outcome.href.toString()
                }

                PutOutcome.ServerNewer -> {
                    // Decision 2: the server wins and the local edit is thrown
                    // away — but only when its replacement is in hand. The row
                    // stays dirty until [apply] overwrites it, so a failed
                    // download costs a retry rather than the edit.
                    refetch += href
                    touched += href
                    pendingDiscard[href] = DiscardedEdit.Cause.SERVER_NEWER
                }

                PutOutcome.Vanished -> {
                    purge(local)
                    touched += href
                    discard(local, DiscardedEdit.Cause.DELETED_ON_SERVER)
                    report = report.copy(deletedLocally = report.deletedLocally + 1)
                }

                is PutOutcome.Rejected ->
                    fail(href, "upload refused: ${outcome.code} ${outcome.message}")

                is PutOutcome.Failed ->
                    fail(href, "upload failed: ${outcome.reason}")

                is PutOutcome.NameTaken ->
                    fail(href, "unexpected 412 on a conditional update")
            }
        }

        private fun stored(local: LocalResource, href: HttpUrl, eTag: String?) {
            val key = href.toString()
            // ⚠️ Only the rows that were in the body. `serialize` writes
            // `local.live`, so a tombstoned override was left out — the PUT *is*
            // its deletion, and the row has no job left. Marking it synced
            // instead strands `is_deleted = 1` behind a cleared `is_dirty`,
            // where no phase can reach it: the ETag we just recorded matches the
            // server's, so nothing re-downloads it and `apply`'s stale-override
            // sweep never runs. The unique index on
            // (list_id, uid, recurrence_id) then makes re-adding that occurrence
            // throw for good.
            //
            // Overrides only. A tombstoned master no longer reaches this phase
            // at all — `markDeleted` tombstones the whole series, so the
            // resource reads as deleted and goes to `deletePhase` — but rows
            // tombstoned by an older version of the app are still out there, and
            // hard-deleting a master here would cascade its live overrides away
            // via `master_id`.
            val (buried, kept) = local.rows.partition { it.isDeleted && it.recurrenceId != null }
            if (buried.isNotEmpty()) store.deleteAll(buried.map { it.id })
            store.markSynced(kept.map { it.id }, key, eTag)
            // Both, because a resource that failed as a create was counted under
            // its UID and is now counted under its href. Clearing one would leak
            // the other into the store forever.
            succeeded(key)
            succeeded(local.key)
            touched += key
            report = report.copy(uploaded = report.uploaded + 1)
        }

        // ------------------------------------------------------------ phase 3

        private fun downloadPhase(
            locals: List<LocalResource>,
            remoteETags: Map<String, String?>,
        ) {
            val byHref = locals.filter { it.href != null }.associateBy { it.href!! }
            val wanted = linkedSetOf<String>()

            remoteETags.forEach { (href, eTag) ->
                val local = byHref[href]
                when {
                    // The write phase gave up on it this run; it holds an edit
                    // that no local row can be matched to yet.
                    href in deferred -> Unit
                    // Never seen it.
                    local == null -> wanted += href
                    // The write phase owns it this run.
                    local.isDirty || local.isDeleted -> Unit
                    // No validator to compare against, so we cannot know.
                    local.eTag == null || eTag == null -> wanted += href
                    eTag != local.eTag -> wanted += href
                }
            }
            wanted += refetch
            // ⚠️ Not on a full reconciliation. The download side has no refund:
            // at THRESHOLD the href is stripped before the fetch, so `apply` —
            // and with it `succeeded` — can never run to clear the count again,
            // and a per-object ACL wrong for an afternoon hides that task for the
            // life of the install. The upload side needs no equivalent, because
            // `stored` and `purge` refund it there. One probe per periodic
            // reconciliation is the expiry: it costs a single resource on a slow
            // cadence, and a resource that answers this time clears its count.
            if (!fullReconciliationDue) wanted.removeAll { isQuarantined(it) }

            fetchAndApply(wanted)
        }

        /** Shared by both read paths: download these hrefs and write them down. */
        private fun fetchAndApply(hrefs: Collection<String>) {
            if (hrefs.isEmpty()) return

            // Both indexes built once. Re-reading the whole list per resource
            // turns a first sync of a large collection into a quadratic scan on
            // the sync thread.
            val rows = store.rowsIn(list.id)
            val index = RowIndex(
                byUid = rows.groupBy { it.uid }.toMutableMap(),
                byHref = rows.filter { it.href != null }.groupBy { it.href!! }.toMutableMap(),
            )

            hrefs.chunked(DOWNLOAD_BATCH).forEach { batch ->
                val fetched = remote.fetch(batch.map(::url)).getOrElse { error ->
                    report = report.copy(failure = "download failed: $error")
                    return
                }
                fetched.resources.forEach { apply(it, index) }
                // ⚠️ Listed by the query, no body from the multiget. It is in the
                // listing, so the sweep leaves it alone, and it never reaches
                // `apply`, so nothing counts it — without this it is re-requested
                // on every sync for ever, which is the loop quarantine exists to
                // break.
                // ⚠️ When the server both omitted hrefs we asked for and
                // volunteered hrefs we did not, "omitted" and "we failed to
                // recognise its spelling" are indistinguishable — and only one
                // of the two verdicts is reversible. A download-side count has
                // no refund: at THRESHOLD the href is stripped before the fetch,
                // so `apply` can never run to clear it. A wasteful re-request is
                // recoverable; a permanent silent drop of a good task is not.
                // ⚠️ Paired by path, not excused wholesale. Real servers answer
                // with hrefs nobody asked for as a matter of course — a sibling,
                // something from another collection — so "any stray at all"
                // would mean a genuinely omitted resource is never counted and
                // is re-requested for ever, which is the loop the count exists
                // to break. Only a stray naming the *same path* is plausibly
                // this href under a spelling we failed to recognise, and that is
                // the one case where counting could permanently drop a resource
                // that is really there.
                val strayPaths = fetched.unsolicited.map { it.pathSegments }.toSet()
                fetched.missing.forEach {
                    val href = it.toString()
                    if (it.pathSegments in strayPaths) {
                        skip(href, "answered under a spelling we did not recognise")
                    } else {
                        fail(href, "listed but not returned")
                    }
                }
                fetched.failed.forEach { failure ->
                    // ⚠️ Quarantining here also silences the upload phase and the
                    // sweep, because a row's quarantine key *is* its href. So the
                    // verdict has to distinguish what the server actually said.
                    val href = failure.href.toString()
                    when (failure.code) {
                        // Gone. The next listing omits it and the sweep purges the
                        // row — counting it would quarantine the resource out of
                        // the very sweep that would have cleaned it up.
                        HTTP_NOT_FOUND, HTTP_GONE ->
                            skip(href, "the server no longer has this resource: ${failure.code}")
                        // The server failing on its own stored object, or on the
                        // batch. A multiget carries no body of ours, so there is
                        // nothing here for a server to reject permanently.
                        0, in 500..599 ->
                            skip(href, "the server could not return this resource: ${failure.code}")
                        // A judgement about this resource — a per-object ACL, or a
                        // share hiding one object. Deterministic, and nothing else
                        // breaks the loop.
                        else ->
                            fail(href, "the server refused this resource: ${failure.code}")
                    }
                }
            }
        }

        private fun apply(
            resource: com.aiexile.caldav.RemoteResource,
            index: RowIndex,
        ) {
            val href = resource.href.toString()
            val todos = runCatching {
                CalendarResource.todosIn(CalendarResource.parse(resource.iCalendar))
            }.getOrElse {
                fail(href, "unreadable iCalendar: $it")
                return
            }
            if (todos.isEmpty()) {
                fail(href, "contains no VTODO")
                return
            }

            val mapped = todos.map { VTodoMapper.read(it, list.id) }
            if (mapped.any { it.uidWasMissing }) {
                fail(href, "a component has no UID")
                return
            }
            // RFC 4791 §4.1: one resource, one UID. More than one is unmappable
            // to rows without inventing an identity the server does not share.
            val uid = mapped.map { it.entity.uid }.distinct().singleOrNull() ?: run {
                fail(href, "holds more than one UID")
                return
            }

            // ⚠️ Only the ETag that arrived with *this* body, and only if strong.
            // A tag from the listing paired with a body from here is not a
            // matched pair, and a weak one cannot be used as `If-Match` at all.
            val eTag = resource.eTag?.takeIf { it.usable }?.value

            val existing = index.byUid[uid].orEmpty().associateBy { it.recurrenceId }

            // ⚠️ Delete-then-recreate at the same URI is reported as a *change*,
            // not as a removal followed by an addition — so the identity in the
            // body is the only thing that says the old task is gone. Rows still
            // holding this href under a different UID are that old task.
            //
            // Read before the writes below, which re-point this href's index
            // entry at the rows we are about to create.
            val displaced = index.byHref[href].orEmpty().filter { it.uid != uid }

            // Captured before the overwrite, because that is what the report is
            // about: the version the user is losing.
            val losing = existing.values.firstOrNull { it.isDirty }

            val master = mapped.firstOrNull { it.entity.recurrenceId == null } ?: mapped.first()
            val masterRow = upsert(master, existing[master.entity.recurrenceId], null, href, eTag)
            val masterId = masterRow.id
            parents[uid] = master.parentUid

            val written = mutableListOf(masterRow)
            mapped.filter { it !== master }.forEach { override ->
                // ⚠️ An override's alarms are its own: RFC 5545 has an override
                // replace the component, so one the server sends without a
                // VALARM has none, whatever its master carries.
                written += upsert(
                    override, existing[override.entity.recurrenceId], masterId, href, eTag,
                )
            }
            val kept = written.map { it.recurrenceId }.toSet()

            // Overrides the server no longer has. Cascade would take them with
            // the master, but the master is still here.
            val stale = existing.filterKeys { it !in kept }.values.map { it.id }
            store.deleteAll(stale)

            // Keep the hoisted index honest for the resources still to come.
            index.byUid[uid] = written
            index.byHref[href] = written
            // ⚠️ Including the href these rows just left. A delete-and-recreate
            // keeps the UID and changes the filename, so the old entry still
            // names rows that now live here — and a later resource in the same
            // batch reading that entry as `displaced` deletes them. The
            // in-memory twin of the sweep's pre-download snapshot.
            val moved = written.map { it.id }.toSet()
            existing.values.mapNotNull { it.href }.toSet().minus(href).forEach { vacated ->
                val left = index.byHref[vacated].orEmpty().filterNot { it.id in moved }
                if (left.isEmpty()) index.byHref.remove(vacated) else index.byHref[vacated] = left
            }

            if (displaced.isNotEmpty()) {
                val ids = displaced.map { it.id }
                store.deleteAll(ids)
                displaced.map { it.uid }.distinct().forEach { other ->
                    index.byUid[other] = index.byUid[other].orEmpty().filterNot { it.id in ids }
                }
                report = report.copy(deletedLocally = report.deletedLocally + 1)
            }

            pendingDiscard.remove(href)?.let { cause ->
                report = report.copy(
                    discardedEdits = report.discardedEdits +
                        DiscardedEdit(uid, losing?.title, cause),
                )
            }

            succeeded(href)
            touched += href
            report = report.copy(downloaded = report.downloaded + 1)
        }

        /** @return the row as it now stands, so the caller can index it. */
        private fun upsert(
            mapped: VTodoMapper.Mapped,
            existing: TaskEntity?,
            masterId: Long?,
            href: String,
            eTag: String?,
        ): TaskEntity {
            val incoming = mapped.entity
            // Local-only columns the server has no opinion about. Taking the
            // mapper's defaults here would silently reset the user's ordering and
            // per-task colour on every download.
            val row = incoming.copy(
                id = existing?.id ?: 0L,
                listId = list.id,
                masterId = masterId,
                parentId = existing?.parentId,
                sortOrder = existing?.sortOrder ?: 0,
                color = existing?.color,
                href = href,
                etag = eTag,
                // Explicit, not defaulted: a downstream write that leaves this
                // set uploads what was just downloaded.
                isDirty = false,
                isDeleted = false,
                // The store's to change, from what the alarms do.
                remindersOff = existing?.remindersOff ?: false,
            )
            // The server's alarms replace ours, as every other field does: a
            // dirty row is never downloaded except to discard its edit.
            val alarms = existing?.let { keepLocalPrecision(mapped.alarms, it, row) } ?: mapped.alarms
            return row.copy(id = store.upsertWithAlarms(row, alarms))
        }

        /**
         * [incoming] with each alarm we uploaded ourselves put back to the lead
         * we hold for it.
         *
         * ⚠️ The wire is coarser than the row: a lead past the end of its
         * anchor is clamped, and a before-start reminder on a task without a
         * start goes out before-due. Taking the round trip at its word would
         * quietly turn a before-start reminder into a before-due one the first
         * time the task is downloaded again.
         * An alarm that comes back as exactly what we would have sent has not
         * been changed, so the row's own lead stands.
         */
        private fun keepLocalPrecision(
            incoming: List<TaskAlarmEntity>,
            existing: TaskEntity,
            task: TaskEntity,
        ): List<TaskAlarmEntity> {
            val local = store.alarmsFor(listOf(existing.id))[existing.id].orEmpty()
            if (local.isEmpty()) return incoming
            return incoming.map { alarm ->
                val mine = local.firstOrNull { it.uid == alarm.uid } ?: return@map alarm
                val sent = AlarmCodec.wireLead(mine, task)
                if (sent == AlarmCodec.Lead(alarm.minutesBefore, alarm.reference)) {
                    alarm.copy(minutesBefore = mine.minutesBefore, reference = mine.reference)
                } else {
                    alarm
                }
            }
        }

        /**
         * Turns the `RELATED-TO` UIDs collected during the download into row ids.
         *
         * Deferred to the end because a parent may arrive in a later batch than
         * its child, and a forward reference resolved eagerly is a lost
         * hierarchy.
         */
        private fun resolveParents() {
            parents.forEach { (uid, parentUid) ->
                val child = store.masterByUid(list.id, uid) ?: return@forEach
                val parent = parentUid?.let { store.masterByUid(list.id, it) }
                if (child.parentId != parent?.id) {
                    store.setParent(child.id, parent?.id)
                }
            }
        }

        // ------------------------------------------------------------ phase 4

        /**
         * @param locals must be read *after* [downloadPhase]. [apply] can re-point
         *   an existing row at a new href when the same UID reappears under a new
         *   filename, and [purge] deletes by row id — so a pre-download list sweeps
         *   away the row the download just repaired.
         */
        private fun sweepPhase(locals: List<LocalResource>, remoteHrefs: Set<String>) {
            // ⚠️ An empty listing never sweeps. The sweep is the one phase that
            // deletes rows it did not see fail, and its evidence is a
            // `calendar-query` with a VTODO comp-filter — a filter some servers
            // mishandle badly enough to answer with an empty *successful*
            // multistatus, which is indistinguishable from an empty collection.
            // Without this floor, one such answer hard-deletes every task the
            // user has in that list, in a single pass, unrecoverably.
            //
            // Cost accepted: a collection genuinely emptied on the server keeps
            // its local rows until one task reappears there. That is recoverable
            // by hand. The other error is not.
            if (remoteHrefs.isEmpty() && locals.any { it.href != null }) {
                report = report.copy(
                    failure = "the server listed no tasks while ${locals.count { it.href != null }} " +
                        "are known here — nothing was deleted",
                )
                return
            }

            locals.forEach { local ->
                val href = local.href ?: return@forEach
                if (href in remoteHrefs || href in touched || isQuarantined(local.key)) return@forEach
                if (local.isDeleted) return@forEach

                // Present locally, absent from a full listing: deleted on the
                // server. A dirty row here is a local edit that lost to that
                // deletion, which the user is told about rather than left to
                // discover.
                if (local.isDirty) discard(local, DiscardedEdit.Cause.DELETED_ON_SERVER)
                purge(local)
                report = report.copy(deletedLocally = report.deletedLocally + 1)
            }
        }

        // ------------------------------------------------------------- shared

        private fun localResources(): List<LocalResource> =
            store.rowsIn(list.id)
                .groupBy { it.uid }
                .map { (uid, rows) -> LocalResource(uid, rows) }

        private fun persistCollectionState(readOnly: Boolean) {
            if (list.isReadOnly == readOnly) return
            // ⚠️ ACL churn is silent: a share can be demoted to read-only with no
            // notification, and a stale flag turns every upload into a 403 the
            // user cannot act on.
            //
            // One column, not the whole row. `list` was captured before the sync
            // started, so writing it back would silently revert a rename, a
            // recolour or a visibility toggle the user made while it ran.
            store.setListReadOnly(list.id, readOnly)
        }

        private fun parentUidOf(row: TaskEntity): String? =
            row.parentId?.let { store.row(it)?.uid }

        private fun uidOf(iCalendar: String): String? = runCatching {
            CalendarResource.todosIn(CalendarResource.parse(iCalendar))
                .firstNotNullOfOrNull { it.property("UID")?.value?.trim() }
        }.getOrNull()

        private fun url(href: String): HttpUrl =
            href.toHttpUrlOrNull() ?: remote.url.resolve(href) ?: remote.url

        private fun purge(local: LocalResource) {
            store.deleteAll(local.rows.map { it.id })
            // The rows are gone, so a counter about them is dead weight.
            succeeded(local.key)
        }

        private fun clearTombstone(local: LocalResource) {
            local.rows.forEach { store.update(it.copy(isDeleted = false, isDirty = false)) }
        }

        private fun discard(local: LocalResource, cause: DiscardedEdit.Cause) {
            report = report.copy(
                discardedEdits = report.discardedEdits +
                    DiscardedEdit(local.uid, local.master.title, cause),
            )
        }

        /** A resource we cannot sync this run, but which is nobody's fault. */
        private fun skip(href: String, reason: String) {
            report = report.copy(
                quarantined = report.quarantined + QuarantinedResource(href, reason, failures = 0),
            )
        }

        /** A resource that failed. Counts towards [QuarantineStore.THRESHOLD]. */
        private fun fail(href: String, reason: String) {
            val key = QuarantineStore.key(list.id, href)
            val failures = (quarantine[key] ?: 0) + 1
            quarantine[key] = failures
            report = report.copy(
                quarantined = report.quarantined + QuarantinedResource(href, reason, failures),
            )
        }

        private fun succeeded(href: String) {
            quarantine.remove(QuarantineStore.key(list.id, href))
        }

        private fun isQuarantined(href: String): Boolean =
            (quarantine[QuarantineStore.key(list.id, href)] ?: 0) >= QuarantineStore.THRESHOLD
    }

    /**
     * The list's rows, indexed both ways [apply] needs them.
     *
     * Kept for the whole download phase and updated in place, because both
     * lookups are per-resource: one to find the rows this UID already has, one to
     * find rows that hold this href under a *different* UID.
     */
    private class RowIndex(
        val byUid: MutableMap<String, List<TaskEntity>>,
        val byHref: MutableMap<String, List<TaskEntity>>,
    )

    /**
     * The rows that make up one calendar resource.
     *
     * A recurring task and its `RECURRENCE-ID` overrides are separate rows and
     * one file: RFC 4791 §4.1 requires everything in a resource to share a UID.
     * So href and ETag belong to the group, never to a row.
     */
    private data class LocalResource(val uid: String, val rows: List<TaskEntity>) {
        val master: TaskEntity = rows.firstOrNull { it.recurrenceId == null } ?: rows.first()
        val href: String? = rows.firstNotNullOfOrNull { it.href }
        val eTag: String? = rows.firstNotNullOfOrNull { it.etag }
        val isDirty: Boolean = rows.any { it.isDirty }

        /**
         * What quarantine counts this resource under.
         *
         * The href once it has one, and the UID before that — a resource that has
         * never been uploaded still has to be countable, or a body the server
         * refuses forever is retried forever.
         */
        val key: String = href ?: "uid:$uid"

        /**
         * The master's tombstone is the resource's: an override cannot outlive
         * the series it belongs to. A deleted *override* is an edit.
         *
         * ⚠️ Not `rows.all { … }`. `markDeleted` now tombstones a series whole,
         * but rows tombstoned by an older version left the master marked and its
         * overrides live — which read as "partly deleted", went to the upload
         * phase, and never sent the DELETE the user asked for. Reading the
         * master repairs those on the next sync instead of leaving them stuck.
         */
        val isDeleted: Boolean = master.isDeleted

        /** What gets serialised: a deleted override is simply absent. */
        val live: List<TaskEntity> = rows.filterNot { it.isDeleted }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410

        /** RFC 5545 `CLASS:CONFIDENTIAL`, as `tasks.classification` stores it. */
        const val CLASS_CONFIDENTIAL = 2

        /**
         * Fresh names tried before giving up on a create.
         *
         * Bounded because every 412 retry loop in this engine is bounded — an
         * unbounded one against a server that 412s unconditionally is a sync that
         * never finishes.
         */
        const val CREATE_ATTEMPTS = 3

        const val DOWNLOAD_BATCH = 30

        /**
         * Pages of `sync-collection` before giving up and reconciling in full.
         *
         * A cap *and* a no-progress guard, because RFC 6578 never requires the
         * token to advance — a server can legitimately truncate forever.
         */
        const val MAX_SYNC_PAGES = 50

        /**
         * Removals in one page below which the sanity threshold does not apply.
         *
         * Deleting a handful of tasks is ordinary; being told the whole
         * collection vanished is what ACL churn looks like.
         */
        const val MIN_REMOVALS_TO_QUESTION = 10
    }
}

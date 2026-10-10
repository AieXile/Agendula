package de.jeanlucmakiola.agendula.data.sync

import de.jeanlucmakiola.agendula.data.tasks.room.TaskEntity
import de.jeanlucmakiola.caldav.CalendarCollection
import de.jeanlucmakiola.caldav.ChangeSet
import de.jeanlucmakiola.caldav.DeleteOutcome
import de.jeanlucmakiola.caldav.ETag
import de.jeanlucmakiola.caldav.FetchResult
import de.jeanlucmakiola.caldav.PushSupport
import de.jeanlucmakiola.caldav.PutOutcome
import de.jeanlucmakiola.caldav.RemoteCalendar
import de.jeanlucmakiola.caldav.RemoteRef
import de.jeanlucmakiola.caldav.RemoteResource
import de.jeanlucmakiola.caldav.TaskCollection
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** An in-memory [SyncStore], standing in for Room. */
class FakeStore(rows: List<TaskEntity> = emptyList()) : SyncStore {

    val rows = rows.toMutableList()
    val readOnlyWrites = mutableListOf<Pair<Long, Boolean>>()

    /** In order, so a test can prove the token was stored *after* its bodies. */
    val tokenWrites = mutableListOf<String?>()
    private var nextId = 1L

    override fun rowsIn(listId: Long) = this.rows.filter { it.listId == listId }

    override fun insert(row: TaskEntity): Long {
        // Computed against the rows as they stand, not at construction: tests
        // seed `rows` directly after building this, and an id fixed up front
        // collides with the first seeded row — which then makes a delete of one
        // take the other with it.
        nextId = maxOf(nextId, (rows.maxOfOrNull { it.id } ?: 0L) + 1)
        val id = nextId++
        rows += row.copy(id = id)
        return id
    }

    override fun update(row: TaskEntity) {
        val index = rows.indexOfFirst { it.id == row.id }
        if (index >= 0) rows[index] = row
    }

    override fun deleteAll(taskIds: List<Long>) {
        rows.removeAll { it.id in taskIds }
    }

    override fun markSynced(taskIds: List<Long>, href: String?, eTag: String?) {
        taskIds.forEach { id ->
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) {
                rows[index] = rows[index].copy(href = href, etag = eTag, isDirty = false)
            }
        }
    }

    override fun setParent(taskId: Long, parentId: Long?) {
        val index = rows.indexOfFirst { it.id == taskId }
        if (index >= 0) rows[index] = rows[index].copy(parentId = parentId)
    }

    override fun masterByUid(listId: Long, uid: String) =
        rows.firstOrNull { it.listId == listId && it.uid == uid && it.recurrenceId == null }

    override fun row(taskId: Long) = rows.firstOrNull { it.id == taskId }

    override fun setListReadOnly(listId: Long, readOnly: Boolean) {
        readOnlyWrites += listId to readOnly
    }

    override fun setSyncToken(listId: Long, token: String?) {
        tokenWrites += token
    }
}

/** An in-memory CalDAV collection that answers like a server rather than a mock. */
class FakeRemote(override val url: HttpUrl = "http://server/dav/tasks/".toHttpUrl()) :
    RemoteCalendar {

    data class Stored(var eTag: ETag?, var body: String)

    val resources = linkedMapOf<String, Stored>()
    val log = mutableListOf<String>()

    var readOnly = false
    var shared = false
    var supportsSyncCollection = false
    var syncToken: String? = null
    var push: PushSupport? = null

    /** Pages the next `changes()` calls will return, in order. */
    val changePages = ArrayDeque<ChangeSet>()
    var stateFailure: String? = null
    var listFailure: String? = null
    var fetchFailure: String? = null

    /** Returns non-null to pre-empt the default behaviour for that href. */
    var onPut: ((HttpUrl) -> PutOutcome?)? = null
    var onDelete: ((HttpUrl) -> DeleteOutcome?)? = null

    /**
     * Returns non-null to pre-empt the default behaviour for that fetch.
     *
     * Unlike [fetchFailure], which breaks every fetch in the run, this can fail
     * one and leave the rest working — the difference between "the server is
     * down" and "this one request timed out".
     */
    var onFetch: ((List<HttpUrl>) -> Result<FetchResult>?)? = null

    fun put(name: String, body: String, eTag: String? = "e-$name") {
        resources[url.newBuilder().addPathSegment(name).build().toString()] =
            Stored(eTag?.let { ETag(it, weak = false) }, body)
    }

    override fun state(): Result<CalendarCollection.State> {
        stateFailure?.let { return Result.failure(IllegalStateException(it)) }
        return Result.success(
            CalendarCollection.State(
                collection = TaskCollection(
                    url = url,
                    displayName = "Tasks",
                    color = null,
                    readOnly = readOnly,
                    isShared = shared,
                    supportsSyncCollection = supportsSyncCollection,
                    maxResourceSize = null,
                    push = push,
                ),
                ctag = "ctag",
                syncToken = syncToken,
            ),
        )
    }

    override fun changes(token: String?): ChangeSet {
        log += "CHANGES $token"
        return changePages.removeFirstOrNull()
            ?: ChangeSet.Failed("the test queued no page for token $token")
    }

    override fun list(): Result<List<RemoteRef>> {
        listFailure?.let { return Result.failure(IllegalStateException(it)) }
        log += "LIST"
        return Result.success(
            resources.map { (href, stored) -> RemoteRef(href.toHttpUrl(), stored.eTag) },
        )
    }

    override fun fetch(hrefs: List<HttpUrl>): Result<FetchResult> {
        log += "FETCH ${hrefs.joinToString(",") { it.pathSegments.last() }}"
        onFetch?.invoke(hrefs)?.let { return it }
        fetchFailure?.let { return Result.failure(IllegalStateException(it)) }
        val found = hrefs.mapNotNull { href ->
            resources[href.toString()]?.let { RemoteResource(href, it.eTag, it.body) }
        }
        return Result.success(
            FetchResult(
                resources = found,
                missing = hrefs.filterNot { it.toString() in resources },
                failed = emptyList(),
                unsolicited = emptyList(),
            ),
        )
    }

    override fun create(name: String, iCalendar: String): PutOutcome {
        val href = url.newBuilder().addPathSegment(name).build()
        log += "CREATE $name"
        onPut?.invoke(href)?.let { return it }
        if (href.toString() in resources) return PutOutcome.NameTaken(href)
        val eTag = ETag("e-$name", weak = false)
        resources[href.toString()] = Stored(eTag, iCalendar)
        return PutOutcome.Stored(href, eTag)
    }

    override fun update(href: HttpUrl, eTag: String?, iCalendar: String): PutOutcome {
        log += "UPDATE ${href.pathSegments.last()} if-match=$eTag"
        onPut?.invoke(href)?.let { return it }
        val stored = resources[href.toString()] ?: return PutOutcome.Vanished
        if (eTag != null && stored.eTag?.value != eTag) return PutOutcome.ServerNewer
        val fresh = ETag("e-${resources.size}-${iCalendar.hashCode()}", weak = false)
        stored.eTag = fresh
        stored.body = iCalendar
        return PutOutcome.Stored(href, fresh)
    }

    override fun delete(href: HttpUrl, eTag: String?): DeleteOutcome {
        log += "DELETE ${href.pathSegments.last()} if-match=$eTag"
        onDelete?.invoke(href)?.let { return it }
        val stored = resources[href.toString()] ?: return DeleteOutcome.Deleted
        if (eTag != null && stored.eTag?.value != eTag) return DeleteOutcome.ServerNewer
        resources.remove(href.toString())
        return DeleteOutcome.Deleted
    }
}

package de.jeanlucmakiola.agendula.data.sync

import de.jeanlucmakiola.agendula.data.tasks.room.TaskEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TasksDatabase
import javax.inject.Inject

/**
 * The database, as [CollectionSyncer] needs it.
 *
 * A seam, and the reason is the same one that put [CalDavGateway] in front of
 * discovery: the reconciliation above this interface is where local edits are
 * discarded, tombstones swept and conflicts resolved, and every one of those is
 * a decision that should be provable without a device. Room's test double is
 * Robolectric plus an in-memory database; this is eight methods.
 */
interface SyncStore {

    /** Every row in a list, **tombstones included**. */
    fun rowsIn(listId: Long): List<TaskEntity>

    fun insert(row: TaskEntity): Long

    fun update(row: TaskEntity)

    fun deleteAll(taskIds: List<Long>)

    /**
     * Records href and ETag on a resource's rows, clearing `is_dirty`.
     *
     * The caller excludes any row it left out of the body — that row is deleted,
     * not marked synced.
     */
    fun markSynced(taskIds: List<Long>, href: String?, eTag: String?)

    fun setParent(taskId: Long, parentId: Long?)

    /** The master row for a UID — the one with no `RECURRENCE-ID`. */
    fun masterByUid(listId: Long, uid: String): TaskEntity?

    fun row(taskId: Long): TaskEntity?

    /**
     * One column, deliberately. A whole-entity update would carry the row as it
     * looked when the sync started and revert anything the user changed while it
     * ran.
     */
    fun setListReadOnly(listId: Long, readOnly: Boolean)

    /** The RFC 6578 cursor. Null resets the collection to a full reconciliation. */
    fun setSyncToken(listId: Long, token: String?)
}

class RoomSyncStore @Inject constructor(
    private val database: TasksDatabase,
) : SyncStore {

    override fun rowsIn(listId: Long) = database.tasks().allIn(listId)

    override fun insert(row: TaskEntity) = database.tasks().insert(row)

    override fun update(row: TaskEntity) {
        database.tasks().update(row)
    }

    override fun deleteAll(taskIds: List<Long>) {
        if (taskIds.isNotEmpty()) database.tasks().deleteAll(taskIds)
    }

    override fun markSynced(taskIds: List<Long>, href: String?, eTag: String?) {
        if (taskIds.isNotEmpty()) database.tasks().markSynced(taskIds, href, eTag)
    }

    override fun setParent(taskId: Long, parentId: Long?) {
        database.tasks().setParent(taskId, parentId)
    }

    override fun masterByUid(listId: Long, uid: String) = database.tasks().byUid(listId, uid)

    override fun row(taskId: Long) = database.tasks().entity(taskId)

    override fun setListReadOnly(listId: Long, readOnly: Boolean) {
        database.taskLists().setReadOnly(listId, readOnly)
    }

    override fun setSyncToken(listId: Long, token: String?) {
        database.taskLists().setSyncToken(listId, token)
    }
}

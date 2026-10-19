package de.jeanlucmakiola.agendula.data.sync

import de.jeanlucmakiola.agendula.data.tasks.room.TaskAlarmEntity
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
 * Robolectric plus an in-memory database; this is a dozen methods.
 */
interface SyncStore {

    /** Every row in a list, **tombstones included**. */
    fun rowsIn(listId: Long): List<TaskEntity>

    fun update(row: TaskEntity)

    /** The reminders of [taskIds] — one resource's rows — by task id, for the body that uploads them. */
    fun alarmsFor(taskIds: List<Long>): Map<Long, List<TaskAlarmEntity>>

    /**
     * Writes a downloaded row — inserted when its id is 0 — and makes its
     * reminders exactly [alarms], in one transaction.
     *
     * ⚠️ One transaction, not an [update] and a second call. The row lands clean
     * with the server's ETag, so if the process died between the two, nothing
     * would ever download it again and the reminders would stay stale for good.
     *
     * A row that is gone by the time it is written — deleted on the device
     * while the sync ran — stays gone, reminders and all.
     *
     * @return the row's id.
     */
    fun upsertWithAlarms(row: TaskEntity, alarms: List<TaskAlarmEntity>): Long

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

    override fun update(row: TaskEntity) {
        database.tasks().update(row)
    }

    override fun alarmsFor(taskIds: List<Long>) =
        if (taskIds.isEmpty()) emptyMap() else database.alarms().forTasks(taskIds).groupBy { it.taskId }

    override fun upsertWithAlarms(row: TaskEntity, alarms: List<TaskAlarmEntity>): Long =
        database.runInTransaction<Long> {
            if (row.id == 0L) {
                database.tasks().insert(row).also { database.alarms().replaceFromServer(it, alarms) }
            } else {
                // ⚠️ Zero rows means the row was deleted under us. Its alarms
                // would then name a task that does not exist, and the foreign
                // key would fail the whole transaction.
                if (database.tasks().update(row) > 0) database.alarms().replaceFromServer(row.id, alarms)
                row.id
            }
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

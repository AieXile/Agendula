package de.jeanlucmakiola.agendula.data.tasks.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import de.jeanlucmakiola.agendula.domain.TaskStatus
import kotlin.time.Instant

/**
 * Reads and writes over `tasks`.
 *
 * Reads split masters from overrides on purpose: [tasks] returns the rows a
 * recurrence expander expands (a non-recurring task is its own single
 * occurrence), and [allOverrides] / [overrides] return the
 * `RECURRENCE-ID` rows that replace individual occurrences. Nothing here
 * expands anything — that is phase 2's job, in Kotlin.
 */
@Dao
interface TaskDao {

    // --- reads ----------------------------------------------------------------

    /**
     * Master (and non-recurring) rows, optionally narrowed to one list. Closed
     * tasks — `COMPLETED` and `CANCELLED` — are excluded unless
     * [includeCompleted]; tombstones always are.
     */
    @Query(
        """
        SELECT t.*, l.name AS list_name, l.color AS list_color, l.is_read_only AS list_read_only,
               a.display_name AS account_display_name
        FROM tasks t
        JOIN task_lists l ON l.id = t.list_id
        LEFT JOIN accounts a ON a.id = l.account_id
        WHERE t.is_deleted = 0
          AND t.master_id IS NULL
          AND (:listId IS NULL OR t.list_id = :listId)
          AND (:includeCompleted = 1 OR t.status NOT IN (2, 3))
        """
    )
    fun tasks(listId: Long?, includeCompleted: Boolean): List<TaskRow>

    @Query(
        """
        SELECT t.*, l.name AS list_name, l.color AS list_color, l.is_read_only AS list_read_only,
               a.display_name AS account_display_name
        FROM tasks t
        JOIN task_lists l ON l.id = t.list_id
        LEFT JOIN accounts a ON a.id = l.account_id
        WHERE t.id = :taskId AND t.is_deleted = 0
        """
    )
    fun task(taskId: Long): TaskRow?

    @Query(
        """
        SELECT t.*, l.name AS list_name, l.color AS list_color, l.is_read_only AS list_read_only,
               a.display_name AS account_display_name
        FROM tasks t
        JOIN task_lists l ON l.id = t.list_id
        LEFT JOIN accounts a ON a.id = l.account_id
        WHERE t.parent_id = :parentTaskId AND t.is_deleted = 0 AND t.master_id IS NULL
        """
    )
    fun subtasks(parentTaskId: Long): List<TaskRow>

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    fun entity(taskId: Long): TaskEntity?

    /**
     * Every override, optionally narrowed to one list — read alongside [tasks] so
     * expansion can replace the occurrences they override in one pass rather than
     * querying per series.
     */
    @Query(
        """
        SELECT * FROM tasks
        WHERE master_id IS NOT NULL AND is_deleted = 0
          AND (:listId IS NULL OR list_id = :listId)
        """
    )
    fun allOverrides(listId: Long?): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE master_id = :masterId AND is_deleted = 0")
    fun overrides(masterId: Long): List<TaskEntity>

    /** Every override of a series, tombstones included — what a move has to carry. */
    @Query("SELECT * FROM tasks WHERE master_id = :masterId")
    fun allOverridesOf(masterId: Long): List<TaskEntity>

    /** Lists holding a DELETE still owed to a server. */
    @Query("SELECT DISTINCT list_id FROM tasks WHERE is_deleted = 1")
    fun listsWithTombstones(): List<Long>

    @Query(
        "SELECT * FROM tasks WHERE master_id = :masterId AND recurrence_id IS :recurrenceId AND is_deleted = 0"
    )
    fun override(masterId: Long, recurrenceId: Instant?): TaskEntity?

    @Query("SELECT * FROM tasks WHERE list_id = :listId AND uid = :uid AND recurrence_id IS :recurrenceId")
    fun byUid(listId: Long, uid: String, recurrenceId: Instant? = null): TaskEntity?

    /** Masters only, tombstones excluded — what an `.ics` export writes. */
    @Query("SELECT * FROM tasks WHERE list_id = :listId AND is_deleted = 0 AND master_id IS NULL")
    fun exportTasks(listId: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE is_dirty = 1")
    fun dirty(): List<TaskEntity>

    /**
     * Every row in a list, tombstones included.
     *
     * Sync needs the tombstones: a row with `is_deleted = 1` is a DELETE the
     * server is still owed, and a query that filters them out is a client that
     * resurrects deleted tasks on the next download.
     */
    @Query("SELECT * FROM tasks WHERE list_id = :listId")
    fun allIn(listId: Long): List<TaskEntity>

    // --- writes ---------------------------------------------------------------

    @Insert
    fun insert(task: TaskEntity): Long

    @Update
    fun update(task: TaskEntity): Int

    @Query(
        """
        UPDATE tasks SET status = :status, percent_complete = :percentComplete,
               completed_at = :completedAt, last_modified = :lastModified, is_dirty = :dirty
        WHERE id = :taskId
        """
    )
    fun setCompletion(
        taskId: Long,
        status: TaskStatus,
        percentComplete: Int?,
        completedAt: Instant?,
        lastModified: Instant?,
        dirty: Boolean,
    ): Int

    /** Hard delete. Used when the row was never on a server. */
    @Query("DELETE FROM tasks WHERE id = :taskId")
    fun delete(taskId: Long): Int

    /**
     * Tombstone, for a row a server still knows about — and for the whole series
     * when [taskId] is a master.
     *
     * ⚠️ `master_id` cascades on *delete*, and a tombstone deletes nothing, so
     * tombstoning the master alone left its overrides live. The resource then
     * read as partly deleted: `LocalResource.isDeleted` is `rows.all { … }`, so
     * it went to the upload phase instead of the delete phase, no DELETE was
     * ever sent, and the master ended `is_deleted = 1, is_dirty = 0` with a
     * matching ETag — beyond the reach of every phase. Other clients kept the
     * task; here it was gone.
     */
    @Query(
        """
        UPDATE tasks SET is_deleted = 1, is_dirty = 1, last_modified = :at
        WHERE id = :taskId OR master_id = :taskId
        """
    )
    fun markDeleted(taskId: Long, at: Instant?): Int

    /**
     * Re-points a subtask at its parent.
     *
     * Separate from [update] because `RELATED-TO` arrives as a UID and can only
     * be resolved to a row id once every row of the sync is present — and
     * resolving it must not disturb `is_dirty`, which a whole-entity update
     * would.
     */
    @Query("UPDATE tasks SET parent_id = :parentId WHERE id = :taskId")
    fun setParent(taskId: Long, parentId: Long?): Int

    /** Hard delete of a whole resource's rows — a master and its overrides. */
    @Query("DELETE FROM tasks WHERE id IN (:taskIds)")
    fun deleteAll(taskIds: List<Long>): Int

    /**
     * Records where a resource lives and which version we hold.
     *
     * Applied to a resource's rows at once, because a master and its
     * `RECURRENCE-ID` overrides share one href and one ETag — they are one file
     * on the server. A tombstoned override is excluded by the caller: it was
     * left out of the body, so the caller deletes the row instead.
     *
     * `is_dirty` is cleared **explicitly** rather than left to a default: a
     * downstream write that leaves the flag set uploads what was just
     * downloaded, which is how a sync loop starts.
     */
    @Query("UPDATE tasks SET href = :href, etag = :etag, is_dirty = 0 WHERE id IN (:taskIds)")
    fun markSynced(taskIds: List<Long>, href: String?, etag: String?): Int
}

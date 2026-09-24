package de.jeanlucmakiola.agendula.data.tasks.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Reads and writes over `task_lists`. Synchronous, like the seam above it. */
@Dao
interface TaskListDao {

    /** The home screen's own order; local only, so it never marks the list for sync. */
    @Query("UPDATE task_lists SET sort_order = :order WHERE id = :listId")
    fun setSortOrder(listId: Long, order: Int): Int

    @Query(
        """
        SELECT l.*, a.display_name AS account_display_name
        FROM task_lists l LEFT JOIN accounts a ON a.id = l.account_id
        ORDER BY a.display_name, l.sort_order, l.name
        """
    )
    fun lists(): List<TaskListRow>

    @Query(
        """
        SELECT l.*, a.display_name AS account_display_name
        FROM task_lists l LEFT JOIN accounts a ON a.id = l.account_id
        WHERE l.id = :listId
        """
    )
    fun list(listId: Long): TaskListRow?

    @Query("SELECT * FROM task_lists WHERE id = :listId")
    fun entity(listId: Long): TaskListEntity?

    @Query("SELECT COUNT(*) FROM task_lists WHERE id = :listId")
    fun exists(listId: Long): Int

    @Insert
    fun insert(list: TaskListEntity): Long

    @Update
    fun update(list: TaskListEntity)

    @Query("UPDATE task_lists SET is_visible = :visible WHERE id = :listId")
    fun setVisible(listId: Long, visible: Boolean)

    /** Attach a list to an account, or detach it with `null`. */
    @Query("UPDATE task_lists SET account_id = :accountId WHERE id = :listId")
    fun setAccount(listId: Long, accountId: Long?)

    @Query("DELETE FROM task_lists WHERE id = :listId")
    fun delete(listId: Long)

    /**
     * Every list belonging to [accountId].
     *
     * Only for rolling back a half-created account. Removing a *working* account
     * must leave its lists alone — `account_id` is `ON DELETE SET NULL` for that
     * reason, and deleting an account is not an instruction to destroy the tasks
     * it held.
     */
    @Query("DELETE FROM task_lists WHERE account_id = :accountId")
    fun deleteForAccount(accountId: Long)

    /**
     * Refreshes just the ACL flag.
     *
     * Not [update]: sync holds a snapshot of the row from before it started, and
     * writing that whole entity back would revert a rename or recolour the user
     * made while it ran.
     */
    @Query("UPDATE task_lists SET is_read_only = :readOnly WHERE id = :listId")
    fun setReadOnly(listId: Long, readOnly: Boolean)

    /**
     * Stores the RFC 6578 cursor.
     *
     * ⚠️ Written **after** a page's bodies are applied, never before — the RFC's
     * own Appendix B has this backwards, and under WorkManager process death
     * mid-sync is routine rather than exotic. A token stored ahead of its bodies
     * is a permanent hole in the collection.
     *
     * One column and one statement, so there is nothing to be half-written.
     */
    @Query("UPDATE task_lists SET sync_token = :token WHERE id = :listId")
    fun setSyncToken(listId: Long, token: String?)

    /**
     * Lists a collection URL may be pointed at instead of inserting a new row:
     * the ones [accountId] already holds, and the ones a previous removal left
     * detached.
     *
     * ⚠️ Both halves matter, and each is a duplicate bug on its own.
     * `remove()` deliberately leaves lists as device-only ones, so re-adding the
     * account must re-attach them or the user gets their old "Personal" full of
     * tasks beside a freshly synced "Personal" holding the same tasks from the
     * server. And re-*authenticating* walks the same picker, so a lookup that
     * only saw detached rows would insert a second copy of every list the
     * account already syncs — there is no unique index on `href` to catch it.
     */
    @Query(
        """
        SELECT * FROM task_lists
        WHERE href IS NOT NULL AND (account_id IS NULL OR account_id = :accountId)
        """
    )
    fun attachable(accountId: Long): List<TaskListEntity>

    /** The synced collections of one account, in the order sync walks them. */
    @Query("SELECT * FROM task_lists WHERE account_id = :accountId AND is_synced = 1 ORDER BY id")
    fun syncedForAccount(accountId: Long): List<TaskListEntity>

    /** Every account's synced collections. */
    @Query("SELECT * FROM task_lists WHERE account_id IS NOT NULL AND is_synced = 1")
    fun observeSynced(): Flow<List<TaskListEntity>>
}

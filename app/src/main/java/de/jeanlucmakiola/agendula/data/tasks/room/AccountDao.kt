package de.jeanlucmakiola.agendula.data.tasks.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** Reads and writes over `accounts`. */
@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY display_name")
    fun all(): List<AccountEntity>

    /**
     * The same rows, observed.
     *
     * ⚠️ A one-shot read cannot show a sync: the worker writes `last_sync_at`
     * from a background thread minutes after the button was pressed, so a screen
     * holding a snapshot goes on saying "never synced" until the user leaves and
     * comes back. Room's invalidation tracker is what closes that gap.
     */
    @Query("SELECT * FROM accounts ORDER BY display_name")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :accountId")
    fun account(accountId: Long): AccountEntity?

    @Insert
    fun insert(account: AccountEntity): Long

    @Update
    fun update(account: AccountEntity)

    @Query("UPDATE accounts SET last_sync_at = :at, last_sync_error = :error WHERE id = :accountId")
    fun recordSync(accountId: Long, at: Instant?, error: String?)

    @Query("DELETE FROM accounts WHERE id = :accountId")
    fun delete(accountId: Long): Int
}

package com.aiexile.agendula.data.tasks.room

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.aiexile.agendula.data.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Folds the write-ahead log back into the database file when the app goes to the
 * background.
 *
 * Room runs in WAL mode, and Auto Backup copies files without checkpointing — so
 * a `-wal` sidecar can hold writes the backed-up `.db` does not. The backup rules
 * carry all three files, which already makes a restore consistent; this narrows
 * the window further by ensuring the `.db` alone is usually current, which is what
 * a restore onto a device that drops the sidecars falls back to.
 */
@Singleton
class DatabaseCheckpoint @Inject constructor(
    private val database: TasksDatabase,
    @ApplicationScope private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                // ⚠️ Stepped, not merely compiled. `query` hands back a lazy
                // cursor and the statement runs on the first fill — closing it
                // unread made this whole class a no-op, so the sidecar kept
                // growing and the `.db` stayed stale, which is precisely the
                // restore case above.
                database.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")
                    .use { it.moveToFirst() }
            }
        }
    }
}

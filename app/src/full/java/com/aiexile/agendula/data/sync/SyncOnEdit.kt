package com.aiexile.agendula.data.sync

import android.util.Log
import com.aiexile.agendula.data.tasks.room.LocalWriteListener
import com.aiexile.agendula.data.tasks.room.TasksDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes a local edit to its account soon after it is made.
 *
 * Hooked into the store's own write paths rather than Room's invalidation
 * tracker: sync writes the same tables, and an observer cannot tell its own
 * downloads from the user's edits.
 */
@Singleton
class SyncOnEdit @Inject constructor(
    private val database: TasksDatabase,
    private val trigger: SyncTrigger,
) : LocalWriteListener {

    override fun onWritten(listIds: Set<Long>) {
        try {
            listIds.mapNotNull { database.taskLists().entity(it)?.accountId }
                .toSet()
                .mapNotNull { database.accounts().account(it)?.displayName }
                .forEach(trigger::pushSoon)
        } catch (e: Exception) {
            // The edit is saved either way; the schedule picks it up later.
            Log.w(TAG, "could not schedule a push", e)
        }
    }

    private companion object {
        const val TAG = "SyncOnEdit"
    }
}

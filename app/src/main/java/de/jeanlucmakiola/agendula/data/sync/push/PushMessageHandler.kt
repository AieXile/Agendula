package de.jeanlucmakiola.agendula.data.sync.push

import android.util.Log
import de.jeanlucmakiola.agendula.data.di.IoDispatcher
import de.jeanlucmakiola.agendula.data.sync.SyncTrigger
import de.jeanlucmakiola.agendula.data.tasks.room.TasksDatabase
import de.jeanlucmakiola.caldav.WebDavPush
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Turns a WebDAV-Push message into a sync of the account it is about. */
@Singleton
class PushMessageHandler @Inject constructor(
    private val database: TasksDatabase,
    private val store: PushStore,
    private val trigger: SyncTrigger,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * @param content the decrypted message body.
     * @param instance the UnifiedPush instance, which is the account id.
     */
    suspend fun handle(content: String, instance: String) = withContext(io) {
        val accountId = instance.toLongOrNull() ?: return@withContext
        val account = database.accounts().account(accountId) ?: return@withContext
        val message = WebDavPush.parse(content)

        val topic = message?.topic
        if (topic != null) {
            // Within this account: a shared calendar has one topic across accounts.
            val list = store.all().values
                .filter { it.support?.topic == topic }
                .firstNotNullOfOrNull { push ->
                    database.taskLists().entity(push.listId)?.takeIf { it.accountId == accountId }
                }
            if (list == null) {
                Log.i(TAG, "push for a topic no synced list has")
                return@withContext
            }
            // Already at that state, e.g. our own write echoed back.
            if (message.syncToken != null && message.syncToken == list.syncToken) return@withContext
        }
        // Without a topic (key rotation, unreadable): a sync re-reads the VAPID key.
        trigger.enqueueFromPush(account.displayName)
    }

    private companion object {
        const val TAG = "PushMessageHandler"
    }
}

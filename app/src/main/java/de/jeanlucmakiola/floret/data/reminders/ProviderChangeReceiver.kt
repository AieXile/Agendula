package de.jeanlucmakiola.floret.data.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-syncs reminders whenever the tasks provider changes — covers external sync
 * (DAVx5 pulling new/edited tasks) while Floret isn't in the foreground. The
 * manifest filter targets both known authorities; best-effort (the in-app
 * ContentObserver covers the foreground case regardless).
 */
@AndroidEntryPoint
class ProviderChangeReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: ReminderScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                scheduler.sync()
            } finally {
                pending.finish()
            }
        }
    }
}

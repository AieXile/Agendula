package de.jeanlucmakiola.agendula.data.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dagger.hilt.android.AndroidEntryPoint
import de.jeanlucmakiola.agendula.data.tasks.ProviderResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-syncs reminders whenever the tasks provider changes — covers external sync
 * (DAVx5 pulling new/edited tasks) while Agendula isn't in the foreground. The
 * manifest filter targets both known authorities; best-effort (the in-app
 * ContentObserver covers the foreground case regardless).
 */
@AndroidEntryPoint
class ProviderChangeReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: ReminderScheduler
    @Inject lateinit var providerResolver: ProviderResolver

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        // The receiver has to stay exported to hear the provider's broadcast, and
        // the sender holds no permission we could require — so validate the
        // broadcast itself. Without this, any installed app can spam a full
        // re-sync (an unbounded provider read) by firing a matching intent.
        if (intent.action != Intent.ACTION_PROVIDER_CHANGED) return
        val authority = providerResolver.resolve()?.authority ?: return
        if (intent.data?.host != authority) return
        // External sync can fire these in bursts; one re-sync per burst is plenty.
        val now = SystemClock.elapsedRealtime()
        synchronized(Companion) {
            if (now - lastSyncAt < MIN_SYNC_INTERVAL_MS) return
            lastSyncAt = now
        }

        val pending = goAsync()
        scope.launch {
            try {
                scheduler.sync()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val MIN_SYNC_INTERVAL_MS = 10_000L

        @Volatile
        var lastSyncAt = -MIN_SYNC_INTERVAL_MS
    }
}

package de.jeanlucmakiola.agendula.data.reminders

import android.app.AlarmManager
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
 * Re-arms reminder alarms after a reboot (alarms don't survive it), an app update, a clock or
 * zone change, or an exact-alarm grant — which only upgrades alarms set after it, so the whole
 * set is cancelled and armed again.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: ReminderScheduler
    @Inject lateinit var snoozeScheduler: ReminderSnoozeScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            val pending = goAsync()
            scope.launch {
                try {
                    runCatching { scheduler.sync(rearmAll = true) }
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val afterReboot = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> true
            // All-day reminders fire at a local wall-clock time, so their instant moves with the zone.
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            -> false
            else -> return
        }
        ReminderMaintenanceWorker.schedule(context)
        val pending = goAsync()
        scope.launch {
            try {
                runCatching { scheduler.sync(afterReboot = afterReboot) }
                if (afterReboot) runCatching { snoozeScheduler.rearm() }
            } finally {
                pending.finish()
            }
        }
    }
}

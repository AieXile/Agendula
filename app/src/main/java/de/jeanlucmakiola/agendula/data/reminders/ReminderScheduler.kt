package de.jeanlucmakiola.agendula.data.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.agendula.data.di.IoDispatcher
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.agendula.data.tasks.ProviderResolver
import de.jeanlucmakiola.agendula.data.tasks.TaskQuery
import de.jeanlucmakiola.agendula.data.tasks.TasksDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The self-scheduled due-reminder engine. Nothing else delivers task reminders —
 * not the platform, not a tasks provider — so Agendula reads upcoming due tasks
 * and arms one exact [AlarmManager] alarm per reminder, within a rolling window. Re-run
 * on app start, on boot, on a store switch, and on an external provider change;
 * it diffs against [ScheduledReminderStore] so only changed alarms move.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataSource: TasksDataSource,
    private val settingsPrefs: SettingsPrefs,
    private val store: ScheduledReminderStore,
    private val providerResolver: ProviderResolver,
    private val snoozeScheduler: ReminderSnoozeScheduler,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val syncLock = Mutex()

    /**
     * Diff the armed alarms against the store and move only what changed.
     *
     * Serialised: the diff is a read-modify-write over [ScheduledReminderStore],
     * and callers overlap (a store switch fires this while the launch sync may
     * still be running). Two interleaved runs would each write their own set as
     * the whole truth, leaving the other's alarms armed but unrecorded — never
     * cancelled, and firing against the wrong store's task ids.
     */
    suspend fun sync(afterReboot: Boolean = false, rearmAll: Boolean = false) =
        withContext(io) { syncLock.withLock { syncLocked(afterReboot, rearmAll) } }

    /** Record that [reminder] went off, so a later pass neither re-arms nor re-fires it. */
    suspend fun markFired(reminder: ScheduledReminder) =
        withContext(io) { syncLock.withLock { store.markFired(reminder) } }

    /**
     * @param afterReboot every alarm is gone, so nothing in the store is armed;
     *   skips asking the system about each one.
     * @param rearmAll cancel every recorded alarm and arm the set afresh — after an
     *   exact-alarm grant, which leaves alarms set before it inexact.
     */
    private suspend fun syncLocked(afterReboot: Boolean, rearmAll: Boolean) {
        val settings = settingsPrefs.settings.first()
        // Gate on whether the store is readable, not on whether a provider
        // resolves: our own store deliberately resolves to no provider, so the
        // latter clears every reminder in the default mode.
        if (!settings.remindersEnabled || !providerResolver.canReadStore()) {
            clearAll()
            return
        }
        val tasks = runCatching { dataSource.tasks(TaskQuery(includeCompleted = false)) }
            .getOrElse { return }
        // Per-task leads. One query for all of them.
        val perTask = runCatching { dataSource.reminders() }.getOrElse { emptyMap() }
        val desired = ReminderPlanner.plan(
            tasks = tasks,
            perTask = perTask,
            settings = settings,
            now = System.currentTimeMillis(),
            zone = ZoneId.systemDefault(),
        )

        // ⚠️ Revoking exact alarms cancels them without telling us, and a grant
        // leaves the old ones inexact — either way the armed set can't be trusted.
        val canExact = canScheduleExact()
        val rearm = rearmAll || store.armedExact() != canExact
        if (rearm) {
            store.all().forEach { cancel(it) }
            runCatching { snoozeScheduler.rearm() }
        }

        // ⚠️ The store survives reboot, force-stop and restore; the alarms don't.
        // Trusting it alone left every reminder after a reboot unarmed for good.
        val fired = store.fired()
        val diff = ReminderDiff.of(store.all(), desired, fired) { !afterReboot && !rearm && isArmed(it) }
        diff.cancel.forEach { cancel(it) }
        // ⚠️ Only what was actually armed. A throw mid-loop used to skip the
        // write below entirely, so every alarm set on that pass went unrecorded
        // — uncancellable, and firing for tasks that no longer exist — and the
        // exception escaped into BootReceiver's goAsync().
        val armed = diff.arm.filter { schedule(it) }
        store.replace(diff.keep + armed, fired = fired intersect desired, exact = canExact)
    }

    /** Whether the system still holds this alarm's PendingIntent. */
    private fun isArmed(reminder: ScheduledReminder): Boolean = pendingIntent(reminder, create = false) != null

    private fun alarmManager(): AlarmManager = context.getSystemService(AlarmManager::class.java)

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager().canScheduleExactAlarms()

    private fun pendingIntent(reminder: ScheduledReminder, create: Boolean): PendingIntent? {
        val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            reminder.requestCode,
            DueReminderReceiver.intent(context, reminder),
            flags,
        )
    }

    /** @return whether the alarm is now armed, and so worth recording. */
    private fun schedule(reminder: ScheduledReminder): Boolean {
        val triggerAt = reminder.triggerAt
        val pi = pendingIntent(reminder, create = true) ?: return false
        val am = alarmManager()
        val canExact = canScheduleExact()
        return try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            true
        } catch (_: IllegalStateException) {
            // The concurrent-alarm ceiling, which [ReminderPlanner.MAX_ALARMS] keeps us under —
            // but the count is per uid and nothing here owns all of it.
            false
        } catch (_: SecurityException) {
            // Exact-alarm permission revoked between the check and the call.
            false
        }
    }

    private fun cancel(reminder: ScheduledReminder) {
        pendingIntent(reminder, create = false)?.let {
            alarmManager().cancel(it)
            it.cancel()
        }
    }

    private suspend fun clearAll() {
        store.all().forEach { cancel(it) }
        store.replace(emptySet(), fired = emptySet())
    }
}

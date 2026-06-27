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
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The self-scheduled due-reminder engine. Tasks providers don't deliver
 * reminders, so Agendula reads upcoming due tasks and arms one exact [AlarmManager]
 * alarm each, within a rolling window. Re-run on app start, boot and provider
 * change; it diffs against [ScheduledReminderStore] so only changed alarms move.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataSource: TasksDataSource,
    private val settingsPrefs: SettingsPrefs,
    private val store: ScheduledReminderStore,
    private val providerResolver: ProviderResolver,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun sync() = withContext(io) {
        val provider = providerResolver.resolve()
        val settings = settingsPrefs.settings.first()
        if (provider == null || !providerResolver.hasPermission(provider) || !settings.remindersEnabled) {
            clearAll()
            return@withContext
        }
        val now = System.currentTimeMillis()
        val horizon = now + WINDOW_MS
        val tasks = runCatching { dataSource.tasks(TaskQuery(includeCompleted = false)) }
            .getOrElse { return@withContext }

        val desired = tasks
            .filter { !it.isClosed && it.due != null }
            .mapNotNull { task ->
                // The task's list may override the global lead, or opt out entirely
                // (override = null), in which case it gets no reminder at all.
                val lead = settings.reminderLeadFor(task.listId) ?: return@mapNotNull null
                task.taskId to (task.due!!.toEpochMilliseconds() - lead.coerceAtLeast(0) * 60_000L)
            }
            .toMap()
            .filterValues { it in now..horizon }

        val previous = store.all()
        (previous.keys - desired.keys).forEach { cancel(it) }
        desired.forEach { (taskId, triggerAt) ->
            if (previous[taskId] != triggerAt) schedule(taskId, triggerAt)
        }
        store.replace(desired)
    }

    private fun alarmManager(): AlarmManager = context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(taskId: Long, create: Boolean): PendingIntent? {
        val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, taskId.toInt(), DueReminderReceiver.intent(context, taskId), flags)
    }

    private fun schedule(taskId: Long, triggerAt: Long) {
        val pi = pendingIntent(taskId, create = true) ?: return
        val am = alarmManager()
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancel(taskId: Long) {
        pendingIntent(taskId, create = false)?.let {
            alarmManager().cancel(it)
            it.cancel()
        }
    }

    private suspend fun clearAll() {
        store.all().keys.forEach { cancel(it) }
        store.replace(emptyMap())
    }

    private companion object {
        const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }
}

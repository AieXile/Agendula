package de.jeanlucmakiola.agendula.data.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.agendula.data.tasks.TaskQuery
import de.jeanlucmakiola.agendula.data.tasks.TasksDataSource
import de.jeanlucmakiola.agendula.domain.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fires when a task's reminder alarm goes off. Re-reads the task (it may have
 * been completed or rescheduled since the alarm was set) and posts only if it's
 * still open.
 */
@AndroidEntryPoint
class DueReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var dataSource: TasksDataSource
    @Inject lateinit var notifier: TaskNotifier
    @Inject lateinit var settingsPrefs: SettingsPrefs
    @Inject lateinit var scheduler: ReminderScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId < 0L) return
        val pending = goAsync()
        scope.launch {
            try {
                val triggerAt = intent.getLongExtra(EXTRA_TRIGGER_AT, -1L)
                val occurrence = intent.getLongExtra(EXTRA_OCCURRENCE, ScheduledReminder.NO_OCCURRENCE)
                if (triggerAt >= 0L) {
                    runCatching { scheduler.markFired(ScheduledReminder(taskId, triggerAt, occurrence)) }
                }
                val settings = settingsPrefs.settings.first()
                if (!settings.remindersEnabled) return@launch
                val task = runCatching { dataSource.occurrence(taskId, occurrence) }.getOrNull()
                if (task != null && !task.isClosed) notifier.postDue(task, settings)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_TASK_ID = "de.jeanlucmakiola.agendula.extra.TASK_ID"
        private const val EXTRA_TRIGGER_AT = "de.jeanlucmakiola.agendula.extra.TRIGGER_AT"
        private const val EXTRA_OCCURRENCE = "de.jeanlucmakiola.agendula.extra.OCCURRENCE_START"

        /**
         * The trigger rides in the intent *data*, not just an extra: PendingIntent
         * identity ignores extras, so two occurrences of the same recurring task
         * would otherwise collapse into one alarm under FLAG_UPDATE_CURRENT.
         */
        fun intent(context: Context, reminder: ScheduledReminder): Intent =
            Intent(context, DueReminderReceiver::class.java)
                .setData("agendula://reminder/${reminder.taskId}/${reminder.triggerAt}".toUri())
                .putExtra(EXTRA_TASK_ID, reminder.taskId)
                .putExtra(EXTRA_TRIGGER_AT, reminder.triggerAt)
                .putExtra(EXTRA_OCCURRENCE, reminder.occurrenceStart)
    }
}

/**
 * The occurrence of [taskId] anchored at [occurrenceStart] (epoch millis), or the
 * task's current one when there is no anchor or it can no longer be found.
 * [TasksDataSource.task] alone resolves a series to whichever occurrence is
 * current, which is not necessarily the one a reminder was armed for.
 */
internal fun TasksDataSource.occurrence(taskId: Long, occurrenceStart: Long): Task? {
    val current = task(taskId) ?: return null
    if (occurrenceStart == ScheduledReminder.NO_OCCURRENCE ||
        current.occurrenceStart?.toEpochMilliseconds() == occurrenceStart
    ) {
        return current
    }
    return tasks(TaskQuery(listId = current.listId, includeCompleted = true))
        .firstOrNull { it.taskId == taskId && it.occurrenceStart?.toEpochMilliseconds() == occurrenceStart }
        ?: current
}

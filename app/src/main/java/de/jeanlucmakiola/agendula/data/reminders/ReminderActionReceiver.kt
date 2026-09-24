package de.jeanlucmakiola.agendula.data.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.agendula.data.tasks.StartupGate
import de.jeanlucmakiola.agendula.data.tasks.TasksDataSource
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Instant

/**
 * The "Done" and "Snooze" buttons on a reminder, plus the re-show when a snooze
 * elapses. All app-internal intents, so the receiver is not exported.
 *
 * - **Done** completes the task — only the reminded occurrence, for a series.
 * - **Snooze** hides the notification and arms [ReminderSnoozeScheduler]'s
 *   alarm, which lives outside [ScheduledReminderStore] so no scheduler pass
 *   can cancel it.
 * - **Show** re-posts it, if the task is still open by then.
 */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {

    @Inject lateinit var notifier: TaskNotifier
    @Inject lateinit var snoozeScheduler: ReminderSnoozeScheduler
    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var repository: TasksRepository
    @Inject lateinit var dataSource: TasksDataSource
    @Inject lateinit var settingsPrefs: SettingsPrefs
    @Inject lateinit var startupGate: StartupGate

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it > 0L } ?: return
        val occurrence = intent.getLongExtra(EXTRA_OCCURRENCE, ScheduledReminder.NO_OCCURRENCE)
        val action = intent.action ?: return
        if (action == ACTION_DONE || action == ACTION_SNOOZE) notifier.cancel(taskId)
        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    ACTION_DONE -> runCatching {
                        startupGate.awaitReady()
                        val anchor = occurrence.takeIf { it != ScheduledReminder.NO_OCCURRENCE }
                            ?.let(Instant::fromEpochMilliseconds)
                        repository.setCompleted(taskId, anchor, completed = true)
                        reminderScheduler.sync()
                    }

                    ACTION_SNOOZE -> runCatching {
                        val minutes = settingsPrefs.settings.first().snoozeMinutes
                        snoozeScheduler.schedule(taskId, occurrence, System.currentTimeMillis() + minutes * 60_000L)
                    }

                    ACTION_SHOW -> runCatching {
                        snoozeScheduler.clear(taskId, occurrence)
                        val settings = settingsPrefs.settings.first()
                        if (!settings.remindersEnabled) return@runCatching
                        startupGate.awaitReady()
                        val task = dataSource.occurrence(taskId, occurrence)
                        if (task != null && !task.isClosed) notifier.postDue(task, settings)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "de.jeanlucmakiola.agendula.reminders.DONE"
        const val ACTION_SNOOZE = "de.jeanlucmakiola.agendula.reminders.SNOOZE"
        const val ACTION_SHOW = "de.jeanlucmakiola.agendula.reminders.SHOW"

        private const val EXTRA_TASK_ID = "de.jeanlucmakiola.agendula.extra.TASK_ID"
        private const val EXTRA_OCCURRENCE = "de.jeanlucmakiola.agendula.extra.OCCURRENCE_START"

        /**
         * The data URI is what keeps two reminders' PendingIntents apart —
         * `filterEquals` never compares extras.
         */
        fun intent(context: Context, action: String, taskId: Long, occurrenceStart: Long): Intent =
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .setData("agendula://reminder-action/$taskId/$occurrenceStart".toUri())
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_OCCURRENCE, occurrenceStart)
    }
}

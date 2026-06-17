package de.jeanlucmakiola.floret.data.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import de.jeanlucmakiola.floret.data.tasks.TasksDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId < 0L) return
        val pending = goAsync()
        scope.launch {
            try {
                val task = runCatching { dataSource.task(taskId) }.getOrNull()
                if (task != null && !task.isClosed) notifier.postDue(task)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_TASK_ID = "de.jeanlucmakiola.floret.extra.TASK_ID"

        fun intent(context: Context, taskId: Long): Intent =
            Intent(context, DueReminderReceiver::class.java).putExtra(EXTRA_TASK_ID, taskId)
    }
}

package de.jeanlucmakiola.agendula.data.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.jeanlucmakiola.agendula.MainActivity
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.domain.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant as JInstant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts one notification per due task on a dedicated channel. The tag is the
 * task id, so a re-fired alarm replaces rather than duplicates. Tapping opens
 * the app (later: the task's detail screen — see [MainActivity]).
 */
@Singleton
class TaskNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // canPost() checks POST_NOTIFICATIONS before we ever call notify().
    @SuppressLint("MissingPermission")
    fun postDue(task: Task) {
        if (!canPost()) return
        ensureChannel()
        val title = task.title.ifBlank { context.getString(R.string.task_untitled) }
        val text = task.due?.let { due ->
            context.getString(R.string.reminder_due_at, formatDue(due.toEpochMilliseconds(), task.isAllDay))
        }
        val tapIntent = PendingIntent.getActivity(
            context,
            task.taskId.toInt(),
            MainActivity.taskIntent(context, task.taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()
        NotificationManagerCompat.from(context).notify(task.taskId.toString(), NOTIFICATION_ID, notification)
    }

    private fun formatDue(millis: Long, allDay: Boolean): String {
        val zone = ZoneId.systemDefault()
        val style = if (allDay) {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        } else {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        }
        return JInstant.ofEpochMilli(millis).atZone(zone)
            .format(style.withLocale(Locale.getDefault()))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.reminder_channel_desc) },
        )
    }

    private companion object {
        const val CHANNEL_ID = "task_reminders"
        const val NOTIFICATION_ID = 1
    }
}

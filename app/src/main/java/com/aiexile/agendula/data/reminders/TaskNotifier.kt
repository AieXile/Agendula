package com.aiexile.agendula.data.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.aiexile.agendula.MainActivity
import com.aiexile.agendula.R
import com.aiexile.agendula.data.prefs.Settings
import com.aiexile.agendula.data.prefs.is24Hour
import com.aiexile.agendula.data.di.ApplicationScope
import com.aiexile.agendula.data.tasks.ProviderResolver
import com.aiexile.agendula.data.tasks.StartupGate
import com.aiexile.agendula.data.tasks.TasksDataSource
import com.aiexile.agendula.domain.Task
import com.aiexile.agendula.domain.calendarDate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant as JInstant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Posts one notification per due task on its list's channel, so each list can
 * have its own sound and importance. The tag is the task id, so a re-fired alarm
 * replaces rather than duplicates. Tapping opens the task's detail screen (see
 * [MainActivity.taskIntent]).
 */
@Singleton
class TaskNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: ProviderResolver,
    private val dataSource: TasksDataSource,
    private val startupGate: StartupGate,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val channelPrefs get() = context.getSharedPreferences(CHANNEL_PREFS, Context.MODE_PRIVATE)

    fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // canPost() checks POST_NOTIFICATIONS before we ever call notify().
    @SuppressLint("MissingPermission")
    fun postDue(task: Task, settings: Settings) {
        if (!canPost()) return
        val channelId = ensureListChannel(task.listId, task.listName)
        val title = task.title.ifBlank { context.getString(R.string.task_untitled) }
        val text = task.due?.let { due ->
            context.getString(
                R.string.reminder_due_at,
                formatReminderDue(
                    due.toEpochMilliseconds(),
                    task.isAllDay,
                    is24Hour = settings.timeFormat.is24Hour(DateFormat.is24HourFormat(context)),
                    relative = RelativeDays(
                        today = context.getString(R.string.reminder_day_today),
                        tomorrow = context.getString(R.string.reminder_day_tomorrow),
                        yesterday = context.getString(R.string.reminder_day_yesterday),
                    ),
                ),
            )
        }
        val tapIntent = PendingIntent.getActivity(
            context,
            task.taskId.toInt(),
            MainActivity.taskIntent(context, task.taskId, task.occurrenceStart?.toEpochMilliseconds()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .addAction(0, context.getString(R.string.reminder_action_done), actionIntent(task, ReminderActionReceiver.ACTION_DONE))
            .addAction(
                0,
                context.getString(R.string.reminder_action_snooze, settings.snoozeMinutes),
                actionIntent(task, ReminderActionReceiver.ACTION_SNOOZE),
            )
            .build()
        NotificationManagerCompat.from(context).notify(task.taskId.toString(), NOTIFICATION_ID, notification)
    }

    fun cancel(taskId: Long) {
        NotificationManagerCompat.from(context).cancel(taskId.toString(), NOTIFICATION_ID)
    }

    private fun actionIntent(task: Task, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        task.taskId.toInt(),
        ReminderActionReceiver.intent(
            context,
            action,
            task.taskId,
            task.occurrenceStart?.toEpochMilliseconds() ?: ScheduledReminder.NO_OCCURRENCE,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )


    /**
     * Creates [listId]'s channel, or renames it after the list, and returns its id.
     * A new channel starts at the importance the old shared channel had, so
     * a user who silenced reminders before per-list channels stays silenced.
     */
    fun ensureListChannel(listId: Long, listName: String?): String {
        ensureGroup()
        val id = "$LIST_CHANNEL_PREFIX${resolver.mode().name.lowercase()}_$listId"
        val importance = manager.getNotificationChannel(id)?.importance ?: legacyImportance()
        manager.createNotificationChannel(
            NotificationChannel(
                id,
                listName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.reminder_channel_name),
                importance,
            ).apply { group = GROUP_ID },
        )
        return id
    }

    /** The old shared channel's importance, remembered past its deletion for lists made later. */
    private fun legacyImportance(): Int =
        manager.getNotificationChannel(LEGACY_CHANNEL_ID)?.importance
            ?: channelPrefs.getInt(KEY_LEGACY_IMPORTANCE, NotificationManager.IMPORTANCE_HIGH)

    private fun ensureGroup() {
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_ID, context.getString(R.string.reminder_channel_name))
                .apply { description = context.getString(R.string.reminder_channel_desc) },
        )
    }

    /**
     * Brings the channels in line with the current store's lists: one per list,
     * named after it and the group in the current language, none for a list that
     * is gone. Retires the old shared channel once its lists have theirs.
     */
    fun refreshChannel() {
        scope.launch {
            runCatching {
                startupGate.awaitReady()
                val lists = dataSource.taskLists()
                lists.forEach { ensureListChannel(it.id, it.name) }
                val prefix = "$LIST_CHANNEL_PREFIX${resolver.mode().name.lowercase()}_"
                val live = lists.map { "$prefix${it.id}" }.toSet()
                manager.notificationChannels
                    .filter { it.id.startsWith(prefix) && it.id !in live }
                    .forEach { manager.deleteNotificationChannel(it.id) }
                manager.getNotificationChannel(LEGACY_CHANNEL_ID)?.let { legacy ->
                    channelPrefs.edit { putInt(KEY_LEGACY_IMPORTANCE, legacy.importance) }
                    manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
                }
            }
        }
    }

    companion object {
        const val GROUP_ID = "task_reminders"
        private const val LEGACY_CHANNEL_ID = "task_reminders"
        private const val LIST_CHANNEL_PREFIX = "reminders_"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_PREFS = "reminder_channels"
        private const val KEY_LEGACY_IMPORTANCE = "legacy_importance"

        /**
         * Whether a reminder can sound anywhere: the group is not blocked and at
         * least one reminder channel is on. No channel yet counts as on.
         */
        fun remindersAudible(manager: NotificationManager): Boolean {
            if (manager.getNotificationChannelGroup(GROUP_ID)?.isBlocked == true) return false
            val channels = manager.notificationChannels.filter { it.group == GROUP_ID || it.id == LEGACY_CHANNEL_ID }
            return channels.isEmpty() || channels.any { it.importance != NotificationManager.IMPORTANCE_NONE }
        }
    }
}

/** The words a reminder uses for the days either side of today. */
internal class RelativeDays(val today: String, val tomorrow: String, val yesterday: String)

/**
 * The due line of a reminder. An all-day due is UTC midnight of its date, so it
 * is read as that date rather than in the device zone, where it lands on the
 * previous day west of Greenwich. With [relative], yesterday to tomorrow read as
 * words and the rest of the coming week as its weekday.
 */
internal fun formatReminderDue(
    millis: Long,
    allDay: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    is24Hour: Boolean? = null,
    relative: RelativeDays? = null,
    today: LocalDate = LocalDate.now(zone),
): String {
    val at = JInstant.ofEpochMilli(millis).atZone(zone)
    val date = if (allDay) Instant.fromEpochMilliseconds(millis).calendarDate(allDay = true) else at.toLocalDate()
    val day = relative?.let { relativeDay(date, today, it, locale) }
        ?: date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    if (allDay) return day
    val time = when (is24Hour) {
        null -> at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        else -> at.format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale))
    }
    return "$day, $time"
}

private fun relativeDay(date: LocalDate, today: LocalDate, words: RelativeDays, locale: Locale): String? {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days == 0L -> words.today
        days == 1L -> words.tomorrow
        days == -1L -> words.yesterday
        days in 2..6 -> date.format(DateTimeFormatter.ofPattern("EEEE", locale))
        else -> null
    }
}

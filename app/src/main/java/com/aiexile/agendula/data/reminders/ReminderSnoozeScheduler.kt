package com.aiexile.agendula.data.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A one-off alarm that re-shows a snoozed reminder. Deliberately separate from
 * [ReminderScheduler]'s diffed set: the reminder it re-shows has already fired,
 * so the next scheduler pass would otherwise treat it as stale and cancel it.
 *
 * Pending snoozes are persisted, since alarms do not survive a reboot; [rearm]
 * puts them back, and one whose time passed while the device was off fires at once.
 */
@Singleton
class ReminderSnoozeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun schedule(taskId: Long, occurrenceStart: Long, triggerAtMillis: Long) {
        dataStore.edit { p ->
            p[KEY] = p[KEY].orEmpty().filterNot { it.startsWith("$taskId|$occurrenceStart|") }.toSet() +
                "$taskId|$occurrenceStart|$triggerAtMillis"
        }
        arm(taskId, occurrenceStart, triggerAtMillis)
    }

    /** Forget a snooze once it has re-shown. */
    suspend fun clear(taskId: Long, occurrenceStart: Long) {
        dataStore.edit { p ->
            p[KEY] = p[KEY].orEmpty().filterNot { it.startsWith("$taskId|$occurrenceStart|") }.toSet()
        }
    }

    suspend fun rearm() {
        val now = System.currentTimeMillis()
        dataStore.data.first()[KEY].orEmpty().forEach { entry ->
            val parts = entry.split('|').map { it.toLongOrNull() }
            val (taskId, occurrence, triggerAt) = parts.takeIf { it.size == 3 && null !in it } ?: return@forEach
            arm(taskId!!, occurrence!!, maxOf(triggerAt!!, now))
        }
    }

    private fun arm(taskId: Long, occurrenceStart: Long, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            ReminderActionReceiver.intent(context, ReminderActionReceiver.ACTION_SHOW, taskId, occurrenceStart),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private companion object {
        /** `taskId|occurrenceStart|triggerAt` per pending snooze. */
        val KEY = stringSetPreferencesKey("pending_snoozes")
    }
}

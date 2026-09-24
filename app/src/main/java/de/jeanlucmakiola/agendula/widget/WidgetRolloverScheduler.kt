package de.jeanlucmakiola.agendula.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The app's own wake-up for the next local midnight, so "Today" on the widget
 * flips over on the day boundary. `ACTION_DATE_CHANGED` isn't delivered to
 * manifest receivers since Android 8, which is why this exists.
 *
 * One inexact alarm at a time, re-armed by every firing: `setAndAllowWhileIdle`
 * needs no permission and survives doze, and a rollover a few minutes late is
 * invisible on a sleeping screen.
 */
object WidgetRolloverScheduler {

    /** Fire just after midnight, so an early delivery doesn't still read the old date. */
    internal val ROLLOVER_SLACK = 5.seconds

    /** Arm the next rollover, or cancel it when no widget is placed. Idempotent. */
    fun sync(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService<AlarmManager>() ?: return
        val pendingIntent = rolloverPendingIntent(appContext)
        if (!hasPlacedWidgets(appContext)) {
            alarmManager.cancel(pendingIntent)
            return
        }
        val triggerAt = nextRolloverAt(Clock.System.now(), TimeZone.currentSystemDefault())
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilliseconds(), pendingIntent,
        )
    }

    /**
     * The instant just after the next local midnight following [now] in [zone].
     * Uses the actual start of day, so it holds where DST skips midnight or a
     * date-line move skips a whole date.
     */
    fun nextRolloverAt(now: Instant, zone: TimeZone): Instant {
        val date = now.toLocalDateTime(zone).date
        var days = 1
        while (days <= MAX_LOOKAHEAD_DAYS) {
            val candidate = date.plus(days, DateTimeUnit.DAY).atStartOfDayIn(zone) + ROLLOVER_SLACK
            if (candidate > now) return candidate
            days++
        }
        // Unreachable for any real zone; an hour rather than the slack so it can't loop.
        return now + 1.hours
    }

    fun hasPlacedWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return manager.getAppWidgetIds(ComponentName(context, TaskWidgetReceiver::class.java)).isNotEmpty()
    }

    private fun rolloverPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ROLLOVER_REQUEST_CODE,
            Intent(context, WidgetUpdateReceiver::class.java)
                .setAction(WidgetUpdateReceiver.ACTION_ROLLOVER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val ROLLOVER_REQUEST_CODE = 0x0DA1

    private const val MAX_LOOKAHEAD_DAYS = 3
}

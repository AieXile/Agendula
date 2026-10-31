package com.aiexile.agendula.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import de.jeanlucmakiola.floret.glance.FloretWidgetUpdateReceiver
import de.jeanlucmakiola.floret.glance.WidgetRollover

/** Every home-screen widget's receiver class — the set [WidgetRollover] checks for placed instances. */
internal val WIDGET_RECEIVER_CLASSES: List<Class<*>> = listOf(
    TaskWidgetReceiver::class.java,
    TodayWidgetReceiver::class.java,
    UpNextWidgetReceiver::class.java,
    ListsWidgetReceiver::class.java,
    WeekWidgetReceiver::class.java,
)

/** Redraws every placed home-screen widget. */
internal suspend fun updateAllWidgets(context: Context) {
    TaskWidget().updateAll(context)
    TodayWidget().updateAll(context)
    UpNextWidget().updateAll(context)
    ListsWidget().updateAll(context)
    WeekWidget().updateAll(context)
}

internal const val ACTION_WIDGET_ROLLOVER = "com.aiexile.agendula.widget.ROLLOVER"

private const val ROLLOVER_REQUEST_CODE = 0x0DA1

internal fun widgetRolloverPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    ROLLOVER_REQUEST_CODE,
    Intent(context, WidgetUpdateReceiver::class.java).setAction(ACTION_WIDGET_ROLLOVER),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

/**
 * Redraws every widget when its day or language goes stale, and keeps the
 * midnight rollover armed — the app-specific glue around
 * [FloretWidgetUpdateReceiver]. Task data changes don't come through here — see
 * [TaskWidgetUpdater].
 */
class WidgetUpdateReceiver : FloretWidgetUpdateReceiver() {
    override val rolloverAction = ACTION_WIDGET_ROLLOVER
    override val widgetReceiverClasses = WIDGET_RECEIVER_CLASSES

    override fun rolloverPendingIntent(context: Context): PendingIntent = widgetRolloverPendingIntent(context)

    override suspend fun redraw(context: Context) {
        updateAllWidgets(context)
    }
}

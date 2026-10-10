package de.jeanlucmakiola.agendula.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import de.jeanlucmakiola.floret.glance.WidgetRollover

/** Host-facing receiver for [TaskWidget]; also keeps the midnight rollover armed. */
class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRollover.sync(context, WIDGET_RECEIVER_CLASSES, ::widgetRolloverPendingIntent)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRollover.sync(context, WIDGET_RECEIVER_CLASSES, ::widgetRolloverPendingIntent)
    }

    /** Self-heal on the system's `updatePeriodMillis` wake-up, should the alarm have been dropped. */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetRollover.sync(context, WIDGET_RECEIVER_CLASSES, ::widgetRolloverPendingIntent)
    }
}

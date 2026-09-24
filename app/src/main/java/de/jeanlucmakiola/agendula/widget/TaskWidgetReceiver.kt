package de.jeanlucmakiola.agendula.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Host-facing receiver for [TaskWidget]; also keeps the midnight rollover armed. */
class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRolloverScheduler.sync(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRolloverScheduler.sync(context)
    }

    /** Self-heal on the system's `updatePeriodMillis` wake-up, should the alarm have been dropped. */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetRolloverScheduler.sync(context)
    }
}

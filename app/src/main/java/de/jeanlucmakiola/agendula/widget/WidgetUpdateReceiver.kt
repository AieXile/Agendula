package de.jeanlucmakiola.agendula.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Redraws the task widget when its day or its language goes stale, and keeps the
 * midnight rollover armed. Task data changes don't come through here — see
 * [TaskWidgetUpdater].
 *
 * Exported for the system broadcasts; an extra redraw from another app is harmless.
 */
class WidgetUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val appContext = context.applicationContext
        val rearm = intent.action in REARM_ACTIONS
        // The host redraws the widget itself after these two.
        val redraw = intent.action !in REARM_ONLY_ACTIONS
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (rearm) WidgetRolloverScheduler.sync(appContext)
                if (redraw && WidgetRolloverScheduler.hasPlacedWidgets(appContext)) {
                    TaskWidget().updateAll(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ROLLOVER = "de.jeanlucmakiola.agendula.widget.ROLLOVER"

        private val REARM_ONLY_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )

        internal val REARM_ACTIONS = REARM_ONLY_ACTIONS + setOf(
            ACTION_ROLLOVER,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
        )

        internal val HANDLED_ACTIONS = REARM_ACTIONS + Intent.ACTION_LOCALE_CHANGED
    }
}

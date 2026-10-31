package com.aiexile.agendula.qs

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.TileService
import com.aiexile.agendula.MainActivity

/**
 * Quick Settings tile: tapping it opens a new task — the same action as the
 * launcher "New task" shortcut. Stateless, so there is no on/off state to keep.
 */
class NewTaskTileService : TileService() {

    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = MainActivity.newTaskIntent(this)
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pending)
            } else {
                startActivityAndCollapse(intent)
            }
        }
    }
}

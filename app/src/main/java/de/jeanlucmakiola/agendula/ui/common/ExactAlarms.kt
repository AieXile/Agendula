package de.jeanlucmakiola.agendula.ui.common

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.net.toUri

/** Whether reminders can fire at their exact time; always true below API 31, where no grant exists. */
fun Context.canScheduleExactAlarms(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

/** The system page where the user grants or revokes exact alarms for this app. */
@RequiresApi(Build.VERSION_CODES.S)
fun Context.exactAlarmSettingsIntent(): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData("package:$packageName".toUri())

fun Context.openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { startActivity(exactAlarmSettingsIntent()) }
}

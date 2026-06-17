package de.jeanlucmakiola.floret

import android.app.Application
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import de.jeanlucmakiola.floret.data.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Registered as android:name=".FloretApp". Besides
 * Hilt init, it kicks off a reminder re-sync on launch (independent of any UI),
 * so alarms reflect tasks synced while the app was closed.
 */
@HiltAndroidApp
class FloretApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val scheduler = EntryPointAccessors
            .fromApplication(this, ReminderEntryPoint::class.java)
            .reminderScheduler()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { scheduler.sync() }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReminderEntryPoint {
        fun reminderScheduler(): ReminderScheduler
    }
}

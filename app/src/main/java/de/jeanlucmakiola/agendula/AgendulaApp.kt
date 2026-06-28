package de.jeanlucmakiola.agendula

import android.app.Application
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import de.jeanlucmakiola.agendula.data.reminders.ReminderScheduler
import de.jeanlucmakiola.floret.crash.CrashConfig
import de.jeanlucmakiola.floret.crash.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Registered as android:name=".AgendulaApp". Besides
 * Hilt init, it kicks off a reminder re-sync on launch (independent of any UI),
 * so alarms reflect tasks synced while the app was closed.
 */
@HiltAndroidApp
class AgendulaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Install first thing so startup crashes are captured too (privacy-
        // respecting, on-device; the user submits the report by hand).
        CrashReporter.install(
            this,
            CrashConfig(
                appLabel = getString(R.string.app_name),
                newIssueUrl = getString(R.string.report_issue_url),
                chooseIssueUrl = getString(R.string.report_issue_url),
                issueTitle = getString(R.string.crash_report_issue_title),
            ),
        )
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

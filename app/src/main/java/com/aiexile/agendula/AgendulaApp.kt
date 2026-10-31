package com.aiexile.agendula

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import com.aiexile.agendula.data.di.ApplicationScope
import com.aiexile.agendula.data.di.ChannelRefresher
import com.aiexile.agendula.data.reminders.ReminderMaintenanceWorker
import com.aiexile.agendula.data.reminders.ReminderScheduler
import com.aiexile.agendula.data.reminders.TaskNotifier
import com.aiexile.agendula.data.tasks.ProviderResolver
import com.aiexile.agendula.data.tasks.StartupGate
import com.aiexile.agendula.data.tasks.provider.ProviderSnapshotSource
import com.aiexile.agendula.data.tasks.room.DatabaseCheckpoint
import com.aiexile.agendula.widget.TaskWidgetUpdater
import de.jeanlucmakiola.floret.crash.CrashConfig
import de.jeanlucmakiola.floret.crash.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Application entry point. Registered as android:name=".AgendulaApp". Besides
 * Hilt init, it kicks off a reminder re-sync on launch (independent of any UI),
 * so alarms reflect tasks synced while the app was closed.
 */
@HiltAndroidApp
class AgendulaApp : Application(), Configuration.Provider {

    /**
     * Lets `@HiltWorker` workers take injected dependencies. The manifest removes
     * WorkManager's default initializer so this one is used instead; without both
     * halves, a worker with a constructor argument fails to instantiate at
     * runtime rather than at build time.
     */
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

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
            ),
        )
        val entryPoint = EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java)
        val scheduler = entryPoint.reminderScheduler()
        // Mirror the stored storage mode into ProviderResolver and import a
        // v0.3.x install's tasks, both before anything reads a store.
        val startupGate = entryPoint.startupGate()
        val scope = entryPoint.applicationScope()
        // An alarm is armed off whichever store was active when it was scheduled,
        // so a switch has to rebuild the set. Armed only once startup's own
        // null -> stored transition is past, which the launch sync below covers.
        val started = AtomicBoolean(false)
        entryPoint.providerResolver().onModeChanged {
            if (started.get()) {
                scope.launch { runCatching { scheduler.sync() } }
                runCatching { entryPoint.taskNotifier().refreshChannel() }
            }
        }
        startupGate.start()
        refreshNotificationChannels()
        ProcessLifecycleOwner.get().lifecycle.addObserver(entryPoint.databaseCheckpoint())
        scope.launch {
            // Wait for the stored mode and the import to land first. Rescheduling
            // alarms against whichever store autoMode happens to pick would arm
            // them off the wrong one — or off an empty one, mid-import.
            runCatching {
                startupGate.awaitReady()
                started.set(true)
                scheduler.sync()
            }
            runCatching { entryPoint.taskWidgetUpdater().start() }
            runCatching { entryPoint.providerSnapshotSource().start() }
            runCatching { ReminderMaintenanceWorker.schedule(this@AgendulaApp) }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshNotificationChannels()
        // Language or dark mode changed: the widget's strings and list colours follow.
        runCatching {
            EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java).taskWidgetUpdater().requestRefresh()
        }
    }

    /** Channel names are stored by the system in whatever language created them. */
    private fun refreshNotificationChannels() {
        val entryPoint = EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java)
        runCatching {
            entryPoint.taskNotifier().refreshChannel()
            entryPoint.channelRefreshers().forEach { it.refreshChannel() }
        }
    }

        @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppEntryPoint {
        fun reminderScheduler(): ReminderScheduler
        fun startupGate(): StartupGate
        fun providerResolver(): ProviderResolver

        @ApplicationScope
        fun applicationScope(): CoroutineScope
        fun databaseCheckpoint(): DatabaseCheckpoint
        fun taskNotifier(): TaskNotifier
        fun channelRefreshers(): Set<@JvmSuppressWildcards ChannelRefresher>
        fun taskWidgetUpdater(): TaskWidgetUpdater
        fun providerSnapshotSource(): ProviderSnapshotSource
    }
}

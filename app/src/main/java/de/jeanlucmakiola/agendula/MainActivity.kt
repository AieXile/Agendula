package de.jeanlucmakiola.agendula

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import de.jeanlucmakiola.agendula.data.prefs.is24Hour
import de.jeanlucmakiola.agendula.ui.common.LocalFirstDayOfWeek
import de.jeanlucmakiola.agendula.ui.common.LocalUse24HourFormat
import de.jeanlucmakiola.agendula.ui.common.localeFirstDayOfWeek
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.jeanlucmakiola.agendula.data.di.LaunchHook
import de.jeanlucmakiola.agendula.data.sync.SyncNoticeNotifier
import de.jeanlucmakiola.agendula.domain.SmartList
import de.jeanlucmakiola.agendula.ui.imports.importIntentUri
import de.jeanlucmakiola.agendula.ui.navigation.AppShortcuts
import de.jeanlucmakiola.agendula.data.prefs.ThemeMode
import de.jeanlucmakiola.agendula.ui.RootScreen
import de.jeanlucmakiola.agendula.ui.crash.CrashReportActivity
import de.jeanlucmakiola.agendula.ui.navigation.NavRequest
import de.jeanlucmakiola.agendula.data.sync.AccountRepository
import de.jeanlucmakiola.agendula.data.sync.PendingLoginFlowStore
import de.jeanlucmakiola.agendula.data.sync.SyncTrigger
import de.jeanlucmakiola.agendula.data.sync.push.PushRegistrar
import de.jeanlucmakiola.agendula.ui.settings.SettingsViewModel
import de.jeanlucmakiola.agendula.ui.theme.AgendulaTheme
import de.jeanlucmakiola.floret.crash.CrashReportDialog
import de.jeanlucmakiola.floret.crash.CrashReporter
import de.jeanlucmakiola.floret.crash.submitCrashReport
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single activity. The theme follows [SettingsViewModel]; [RootScreen] is the
 * (replaceable) functional scaffold over the real data layer. Notification taps
 * arrive as a [NavRequest] (see [navRequestOf]).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var launchHooks: Set<@JvmSuppressWildcards LaunchHook>

    @Inject lateinit var accounts: AccountRepository

    @Inject lateinit var syncTrigger: SyncTrigger

    @Inject lateinit var pendingLoginFlows: PendingLoginFlowStore

    @Inject lateinit var push: PushRegistrar

    // A captured crash report awaiting the user's decision, surfaced as a dialog
    // over the app on the next launch (the single-crash path). A startup
    // crash-loop is handled out of band, before setContent — see below.
    private var pendingCrashReport by mutableStateOf<String?>(null)

    // A notification tap's destination, handed to the nav host and cleared once taken.
    private var navRequest by mutableStateOf<NavRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the app keeps crashing as it starts, the main UI can't be trusted
        // to come up. Route to the standalone report screen instead of
        // re-entering the crashing graph.
        if (CrashReporter.isCrashLoop(this)) {
            startActivity(Intent(this, CrashReportActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        AppShortcuts.publish(this)

        // Only on a fresh launch: after a rotation or process restore the intent
        // is the same old one and the back stack already holds its destination.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) navRequest = navRequestOf(intent)

        // Surface a single captured crash as a dialog on the next launch.
        if (CrashReporter.shouldPrompt(this)) pendingCrashReport = CrashReporter.pendingReport(this)

        // Sync hard on app open: the periodic worker's interval is a floor, and
        // in the `rare` and `restricted` App Standby buckets it may not have run
        // at all. `KEEP` makes rescheduling idempotent, so this also repairs a
        // schedule lost to "clear app data" or to a restore.
        //
        // ⚠️ Only on a genuine open. This activity declares no `configChanges`,
        // so onCreate runs again on every rotation, theme switch, locale change
        // and font-scale change — each of which would otherwise start a fresh
        // network sync the moment the previous one finished.
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                runCatching {
                    accounts.rescheduleAll()
                    accounts.syncable().forEach { syncTrigger.enqueue(it.displayName) }
                    // A login flow the previous process died in the middle of.
                    // Its password, if the user approved, exists nowhere else.
                    pendingLoginFlows.reclaim()
                }
                // Last and on its own: it waits on the network, and must not
                // hold up the reclaim above. Re-registering on open is what
                // the connector recommends.
                runCatching { push.updateAll() }
            }
        }

        // Variant hooks: the demo seeder in debug builds, nothing in release.
        if (savedInstanceState == null) {
            lifecycleScope.launch { launchHooks.forEach { runCatching { it.onLaunch(intent) } } }
        }
        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val ui by settingsViewModel.state.collectAsStateWithLifecycle()
            val darkTheme = when (ui.settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val context = LocalContext.current
            val use24Hour = ui.settings.timeFormat.is24Hour(DateFormat.is24HourFormat(context))
            val firstDayOfWeek = ui.settings.weekStart ?: localeFirstDayOfWeek()
            AgendulaTheme(darkTheme = darkTheme, dynamicColor = ui.settings.dynamicColor) {
                CompositionLocalProvider(
                    LocalUse24HourFormat provides use24Hour,
                    LocalFirstDayOfWeek provides firstDayOfWeek,
                ) {
                    RootScreen(
                        modifier = Modifier.fillMaxSize(),
                        navRequest = navRequest,
                        onNavRequestConsumed = { navRequest = null },
                    )
                    pendingCrashReport?.let { report ->
                        CrashReportDialog(
                            report = report,
                            onSend = {
                                submitCrashReport(this@MainActivity, report)
                                CrashReporter.clearReport(this@MainActivity)
                                pendingCrashReport = null
                            },
                            onDismiss = {
                                CrashReporter.dismissPrompt(this@MainActivity)
                                pendingCrashReport = null
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navRequestOf(intent)?.let { navRequest = it }
    }

    override fun onResume() {
        super.onResume()
        // A successful start breaks any loop; reset the timing trail so a later
        // ordinary crash isn't mistaken for a loop.
        CrashReporter.markHealthy(this)
    }

    companion object {
        const val EXTRA_TASK_ID = "de.jeanlucmakiola.agendula.extra.TASK_ID"
        const val EXTRA_OCCURRENCE_START = "de.jeanlucmakiola.agendula.extra.OCCURRENCE_START"
        private const val EXTRA_OPEN_ACCOUNTS = "de.jeanlucmakiola.agendula.extra.OPEN_ACCOUNTS"
        const val ACTION_NEW_TASK = "de.jeanlucmakiola.agendula.action.NEW_TASK"
        const val ACTION_TODAY = "de.jeanlucmakiola.agendula.action.TODAY"
        private const val ACTION_OPEN_SMART = "de.jeanlucmakiola.agendula.action.OPEN_SMART"
        private const val ACTION_OPEN_LIST = "de.jeanlucmakiola.agendula.action.OPEN_LIST"
        private const val EXTRA_SMART_LIST = "de.jeanlucmakiola.agendula.extra.SMART_LIST"
        private const val EXTRA_LIST_ID = "de.jeanlucmakiola.agendula.extra.LIST_ID"
        private const val SHARED_TITLE_LIMIT = 500
        private const val NO_OCCURRENCE = -1L

        /**
         * Opens a task's detail (reminder taps). [occurrenceStart] (epoch millis)
         * picks the occurrence of a recurring task.
         */
        fun taskIntent(context: Context, taskId: Long, occurrenceStart: Long? = null): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_OCCURRENCE_START, occurrenceStart ?: NO_OCCURRENCE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /** Opens Settings → Accounts, where the sync notice's detail lives. */
        fun openIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_ACCOUNTS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The launcher shortcut's and Quick Settings tile's "New task". */
        fun newTaskIntent(context: Context): Intent =
            Intent(ACTION_NEW_TASK, null, context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun todayIntent(context: Context): Intent =
            Intent(ACTION_TODAY, null, context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens one smart list; the data URI keeps each widget's PendingIntent apart. */
        fun smartListIntent(context: Context, list: SmartList): Intent =
            Intent(ACTION_OPEN_SMART, "agendula://smart/${list.name}".toUri(), context, MainActivity::class.java)
                .putExtra(EXTRA_SMART_LIST, list.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens one real list. */
        fun listIntent(context: Context, listId: Long): Intent =
            Intent(ACTION_OPEN_LIST, "agendula://list/$listId".toUri(), context, MainActivity::class.java)
                .putExtra(EXTRA_LIST_ID, listId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        internal fun navRequestOf(intent: Intent?): NavRequest? {
            if (intent == null) return null
            intent.getLongExtra(SyncNoticeNotifier.EXTRA_SIGN_IN_ACCOUNT_ID, -1L).takeIf { it > 0L }
                ?.let { return NavRequest.OpenAccount(it) }
            if (intent.getBooleanExtra(EXTRA_OPEN_ACCOUNTS, false)) return NavRequest.OpenAccounts
            when (intent.action) {
                ACTION_NEW_TASK -> return NavRequest.NewTask()
                ACTION_TODAY -> return NavRequest.OpenSmart(SmartList.TODAY)
                ACTION_OPEN_SMART -> intent.getStringExtra(EXTRA_SMART_LIST)
                    ?.let { name -> SmartList.entries.firstOrNull { it.name == name } }
                    ?.let { return NavRequest.OpenSmart(it) }
                ACTION_OPEN_LIST -> intent.getLongExtra(EXTRA_LIST_ID, -1L).takeIf { it > 0L }
                    ?.let { return NavRequest.OpenList(it) }
            }
            importIntentUri(intent)?.let { return NavRequest.Import(it) }
            if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/plain") == true) {
                val shared = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                return NavRequest.NewTask(shared?.trim()?.take(SHARED_TITLE_LIMIT))
            }
            val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it > 0L } ?: return null
            val occurrence = intent.getLongExtra(EXTRA_OCCURRENCE_START, NO_OCCURRENCE)
                .takeIf { it != NO_OCCURRENCE }
            return NavRequest.OpenTask(taskId, occurrence)
        }
    }
}

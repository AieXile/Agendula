package de.jeanlucmakiola.agendula

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.jeanlucmakiola.agendula.data.demo.DemoSeeder
import de.jeanlucmakiola.agendula.data.prefs.ThemeMode
import de.jeanlucmakiola.agendula.ui.RootScreen
import de.jeanlucmakiola.agendula.ui.crash.CrashReportActivity
import de.jeanlucmakiola.agendula.ui.settings.SettingsViewModel
import de.jeanlucmakiola.agendula.ui.theme.AgendulaTheme
import de.jeanlucmakiola.floret.crash.CrashReportDialog
import de.jeanlucmakiola.floret.crash.CrashReporter
import de.jeanlucmakiola.floret.crash.submitCrashReport
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single activity. The theme follows [SettingsViewModel]; [RootScreen] is the
 * (replaceable) functional scaffold over the real data layer. Task-detail intent
 * routing for reminder taps lands with the full UI.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var demoSeeder: DemoSeeder

    // A captured crash report awaiting the user's decision, surfaced as a dialog
    // over the app on the next launch (the single-crash path). A startup
    // crash-loop is handled out of band, before setContent — see below.
    private var pendingCrashReport by mutableStateOf<String?>(null)

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

        // Surface a single captured crash as a dialog on the next launch.
        if (CrashReporter.shouldPrompt(this)) pendingCrashReport = CrashReporter.pendingReport(this)

        // Debug-only sample data: `am start ... --ez agendula_seed true`. Seeds a
        // local (non-syncing) demo list once; no-op without the extra.
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_SEED, false)) {
            lifecycleScope.launch { runCatching { demoSeeder.seed() } }
        }
        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val ui by settingsViewModel.state.collectAsStateWithLifecycle()
            val darkTheme = when (ui.settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AgendulaTheme(darkTheme = darkTheme, dynamicColor = ui.settings.dynamicColor) {
                RootScreen(modifier = Modifier.fillMaxSize())
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

    override fun onResume() {
        super.onResume()
        // A successful start breaks any loop; reset the timing trail so a later
        // ordinary crash isn't mistaken for a loop.
        CrashReporter.markHealthy(this)
    }

    companion object {
        const val EXTRA_TASK_ID = "de.jeanlucmakiola.agendula.extra.TASK_ID"
        private const val EXTRA_SEED = "agendula_seed"

        /** Opens the app focused on a task (reminder taps). Routing lands with the UI. */
        fun taskIntent(context: Context, taskId: Long): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

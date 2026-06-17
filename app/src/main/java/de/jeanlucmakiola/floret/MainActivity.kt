package de.jeanlucmakiola.floret

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.jeanlucmakiola.floret.data.demo.DemoSeeder
import de.jeanlucmakiola.floret.data.prefs.ThemeMode
import de.jeanlucmakiola.floret.ui.RootScreen
import de.jeanlucmakiola.floret.ui.settings.SettingsViewModel
import de.jeanlucmakiola.floret.ui.theme.FloretTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Debug-only sample data: `am start ... --ez floret_seed true`. Seeds a
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
            FloretTheme(darkTheme = darkTheme, dynamicColor = ui.settings.dynamicColor) {
                RootScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "de.jeanlucmakiola.floret.extra.TASK_ID"
        private const val EXTRA_SEED = "floret_seed"

        /** Opens the app focused on a task (reminder taps). Routing lands with the UI. */
        fun taskIntent(context: Context, taskId: Long): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

package de.jeanlucmakiola.floret

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import de.jeanlucmakiola.floret.ui.RootScreen
import de.jeanlucmakiola.floret.ui.theme.FloretTheme

/**
 * Single activity. M0 hosts only a themed placeholder scaffold; the task
 * screens, theme-mode wiring (SettingsViewModel) and notification/widget
 * intent routing land in later milestones — mirroring Calendula's MainActivity.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FloretTheme {
                RootScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

package de.jeanlucmakiola.agendula.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.data.prefs.Settings
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.agendula.data.prefs.ThemeMode
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.agendula.domain.SmartList
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.ui.theme.AgendulaTheme
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.components.pastelize
import de.jeanlucmakiola.floret.components.positionOf
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Picks what one widget instance shows: a smart list or a real list. Runs when
 * the widget is placed (below API 31) and on "reconfigure" from the launcher.
 */
@AndroidEntryPoint
class TaskWidgetConfigActivity : ComponentActivity() {

    @Inject lateinit var repository: TasksRepository
    @Inject lateinit var settingsPrefs: SettingsPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out cancels the placement, as the framework expects.
        setResult(RESULT_CANCELED, resultIntent(appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        enableEdgeToEdge()
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        val listsFlow = runCatching { repository.taskLists() }.getOrDefault(emptyFlow())
            .catch { emit(emptyList()) }

        setContent {
            val settings by settingsPrefs.settings.collectAsState(initial = Settings())
            val lists by remember { listsFlow }.collectAsState(initial = emptyList())
            var current by remember { mutableStateOf<TaskFilter?>(null) }
            LaunchedEffect(glanceId) {
                current = parseWidgetFilter(
                    getAppWidgetState(this@TaskWidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId)[WIDGET_FILTER_KEY],
                )
            }
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AgendulaTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
                WidgetFilterPicker(
                    lists = lists.filter { it.isVisible },
                    dark = dark,
                    selected = current ?: DEFAULT_WIDGET_FILTER,
                    onSelect = { filter ->
                        lifecycleScope.launch {
                            updateAppWidgetState(this@TaskWidgetConfigActivity, glanceId) {
                                it[WIDGET_FILTER_KEY] = filter.widgetStorageValue()
                            }
                            TaskWidget().update(this@TaskWidgetConfigActivity, glanceId)
                            setResult(RESULT_OK, resultIntent(appWidgetId))
                            finish()
                        }
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private fun resultIntent(appWidgetId: Int) =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

@Composable
private fun WidgetFilterPicker(
    lists: List<TaskList>,
    selected: TaskFilter,
    dark: Boolean,
    onSelect: (TaskFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    FullScreenPicker(title = stringResource(R.string.widget_config_title), onDismiss = onDismiss) {
        GroupedSectionHeader(stringResource(R.string.widget_config_smart_lists))
        WIDGET_SMART_LISTS.forEachIndexed { index, smart ->
            val filter = TaskFilter.Smart(smart)
            val isSelected = filter == selected
            GroupedRow(
                title = stringResource(smartListLabel(smart)),
                position = positionOf(index, WIDGET_SMART_LISTS.size),
                selected = isSelected,
                trailing = if (isSelected) ({ SelectedCheck() }) else null,
                onClick = { onSelect(filter) },
            )
        }
        if (lists.isNotEmpty()) {
            GroupedSectionHeader(stringResource(R.string.lists_header))
            lists.forEachIndexed { index, list ->
                val filter = TaskFilter.OfList(list.id)
                val isSelected = filter == selected
                GroupedRow(
                    title = list.name,
                    position = positionOf(index, lists.size),
                    selected = isSelected,
                    leading = { Icon(Icons.Rounded.Circle, contentDescription = null, tint = pastelize(list.color, dark)) },
                    trailing = if (isSelected) ({ SelectedCheck() }) else null,
                    onClick = { onSelect(filter) },
                )
            }
        }
    }
}

internal fun smartListLabel(list: SmartList): Int = when (list) {
    SmartList.TODAY -> R.string.smart_today
    SmartList.UPCOMING -> R.string.smart_upcoming
    SmartList.OVERDUE -> R.string.smart_overdue
    else -> R.string.smart_all
}

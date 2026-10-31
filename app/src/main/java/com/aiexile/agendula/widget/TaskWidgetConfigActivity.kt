package com.aiexile.agendula.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import com.aiexile.agendula.R
import com.aiexile.agendula.data.prefs.Settings
import com.aiexile.agendula.data.prefs.SettingsPrefs
import com.aiexile.agendula.data.prefs.ThemeMode
import com.aiexile.agendula.data.tasks.TasksRepository
import com.aiexile.agendula.domain.SavedFilter
import com.aiexile.agendula.domain.SmartList
import com.aiexile.agendula.domain.TaskFilter
import com.aiexile.agendula.domain.TaskList
import com.aiexile.agendula.ui.theme.AgendulaTheme
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.GroupedSectionHeader
import de.jeanlucmakiola.floret.components.OptionPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.components.pastelize
import de.jeanlucmakiola.floret.components.positionOf
import de.jeanlucmakiola.floret.glance.WidgetBackground
import de.jeanlucmakiola.floret.glance.WidgetThemeMode
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which widget provider a configure session is for — decides which sections [WidgetFilterPicker] shows. */
internal enum class WidgetKind { TASKS, TODAY, UP_NEXT, LISTS, WEEK }

/** Reads [appWidgetId]'s provider class from the host, since that's the only thing naming which widget this is. */
private fun widgetKindOf(context: Context, appWidgetId: Int): WidgetKind? {
    val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.provider ?: return null
    return when (provider.className) {
        TaskWidgetReceiver::class.java.name -> WidgetKind.TASKS
        TodayWidgetReceiver::class.java.name -> WidgetKind.TODAY
        UpNextWidgetReceiver::class.java.name -> WidgetKind.UP_NEXT
        ListsWidgetReceiver::class.java.name -> WidgetKind.LISTS
        WeekWidgetReceiver::class.java.name -> WidgetKind.WEEK
        else -> null
    }
}

private fun widgetFor(kind: WidgetKind): GlanceAppWidget = when (kind) {
    WidgetKind.TASKS -> TaskWidget()
    WidgetKind.TODAY -> TodayWidget()
    WidgetKind.UP_NEXT -> UpNextWidget()
    WidgetKind.LISTS -> ListsWidget()
    WidgetKind.WEEK -> WeekWidget()
}

/**
 * Picks what one widget instance shows. [WidgetKind.TASKS] picks a smart
 * list or one or more real lists (with an optional "group by list" sort);
 * [WidgetKind.LISTS] picks which lists get a tile; [WidgetKind.UP_NEXT] can be
 * narrowed to one list; every other kind only
 * offers background and theme, since they're always the smart-list scope.
 * Runs when the widget is placed (below API 31) and on "reconfigure" from
 * the launcher.
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
        val kind = widgetKindOf(this, appWidgetId) ?: WidgetKind.TASKS
        enableEdgeToEdge()
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        val listsFlow = runCatching { repository.taskLists() }.getOrDefault(emptyFlow())
            .catch { emit(emptyList()) }

        setContent {
            val settings by settingsPrefs.settings.collectAsState(initial = Settings())
            val lists by remember { listsFlow }.collectAsState(initial = emptyList())
            val savedFilters by settingsPrefs.savedFilters.collectAsState(initial = emptyList())
            var current by remember { mutableStateOf<TaskFilter?>(null) }
            var groupByList by remember { mutableStateOf(false) }
            var background by remember { mutableStateOf(WidgetBackground.SOLID) }
            var themeOverride by remember { mutableStateOf<WidgetThemeMode?>(null) }
            var rowColor by remember { mutableStateOf(WidgetRowColor.NEUTRAL) }
            var selectedListIds by remember { mutableStateOf<Set<Long>?>(null) }
            LaunchedEffect(glanceId) {
                val state = getAppWidgetState(this@TaskWidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId)
                current = parseWidgetFilter(state[WIDGET_FILTER_KEY])
                groupByList = state[WIDGET_GROUP_BY_LIST_KEY] ?: false
                background = parseWidgetBackground(state[WIDGET_BACKGROUND_KEY], kind)
                themeOverride = parseWidgetThemeOverride(state[WIDGET_THEME_KEY])
                rowColor = parseWidgetRowColor(state[WIDGET_ROW_COLOR_KEY])
                selectedListIds = parseWidgetListIds(state[WIDGET_LIST_IDS_KEY])
            }
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AgendulaTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
                WidgetFilterPicker(
                    kind = kind,
                    lists = lists.filter { it.isVisible },
                    savedFilters = savedFilters,
                    dark = dark,
                    selected = current ?: DEFAULT_WIDGET_FILTER,
                    groupByList = groupByList,
                    onGroupByListChange = { groupByList = it },
                    background = background,
                    onBackgroundChange = { background = it },
                    themeOverride = themeOverride,
                    onThemeOverrideChange = { themeOverride = it },
                    rowColor = rowColor,
                    onRowColorChange = { rowColor = it },
                    selectedListIds = selectedListIds,
                    onSelectedListIdsChange = { selectedListIds = it },
                    onSave = { filter ->
                        lifecycleScope.launch {
                            updateAppWidgetState(this@TaskWidgetConfigActivity, glanceId) {
                                when (kind) {
                                    WidgetKind.TASKS -> {
                                        it[WIDGET_FILTER_KEY] = filter.widgetStorageValue()
                                        it[WIDGET_GROUP_BY_LIST_KEY] = groupByList
                                        it[WIDGET_ROW_COLOR_KEY] = rowColor.name
                                    }
                                    WidgetKind.LISTS, WidgetKind.UP_NEXT -> {
                                        selectedListIds?.let { ids -> it[WIDGET_LIST_IDS_KEY] = ids.widgetListIdsStorageValue() }
                                            ?: it.remove(WIDGET_LIST_IDS_KEY)
                                    }
                                    WidgetKind.TODAY, WidgetKind.WEEK -> Unit
                                }
                                it[WIDGET_BACKGROUND_KEY] = background.name
                                themeOverride?.let { mode -> it[WIDGET_THEME_KEY] = mode.name }
                                    ?: it.remove(WIDGET_THEME_KEY)
                            }
                            widgetFor(kind).update(this@TaskWidgetConfigActivity, glanceId)
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
    kind: WidgetKind,
    lists: List<TaskList>,
    savedFilters: List<SavedFilter>,
    selected: TaskFilter,
    dark: Boolean,
    groupByList: Boolean,
    onGroupByListChange: (Boolean) -> Unit,
    background: WidgetBackground,
    onBackgroundChange: (WidgetBackground) -> Unit,
    themeOverride: WidgetThemeMode?,
    onThemeOverrideChange: (WidgetThemeMode?) -> Unit,
    rowColor: WidgetRowColor,
    onRowColorChange: (WidgetRowColor) -> Unit,
    selectedListIds: Set<Long>?,
    onSelectedListIdsChange: (Set<Long>?) -> Unit,
    onSave: (TaskFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var showBackgroundPicker by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showRowColorPicker by remember { mutableStateOf(false) }
    // Smart lists, saved filters and real lists are mutually exclusive; only real
    // lists support picking more than one. `selectedSmart` or `selectedFilter`
    // non-null is that pick; otherwise (or before the stored filter loads) it's
    // a list pick.
    var selectedSmart by remember(selected) { mutableStateOf((selected as? TaskFilter.Smart)?.list) }
    var selectedFilter by remember(selected) { mutableStateOf((selected as? TaskFilter.Saved)?.id) }
    var selectedLists by remember(selected) {
        mutableStateOf(
            when (selected) {
                is TaskFilter.OfList -> setOf(selected.listId)
                is TaskFilter.OfLists -> selected.listIds
                is TaskFilter.Smart, is TaskFilter.Tagged, is TaskFilter.Saved -> emptySet()
            },
        )
    }
    // A widget whose filter was deleted keeps the dead id until something else is picked.
    val liveFilter = selectedFilter?.takeIf { id -> savedFilters.any { it.id == id } }
    val canSave = kind != WidgetKind.TASKS || selectedSmart != null || liveFilter != null ||
        selectedLists.isNotEmpty()

    FullScreenPicker(
        title = stringResource(if (kind == WidgetKind.LISTS) R.string.widget_lists_config_title else R.string.widget_config_title),
        onDismiss = onDismiss,
        actions = {
            TextButton(
                enabled = canSave,
                onClick = {
                    // The toggle row only shows (and so can only be turned off) with
                    // more than one list selected; narrowing back down to one, or to
                    // a smart list, has to clear it here instead; otherwise it's
                    // stuck on with no way left to see or undo it.
                    if (selectedLists.size <= 1 && groupByList) onGroupByListChange(false)
                    when {
                        selectedSmart != null -> onSave(TaskFilter.Smart(selectedSmart!!))
                        liveFilter != null -> onSave(TaskFilter.Saved(liveFilter))
                        else -> onSave(TaskFilter.OfLists(selectedLists))
                    }
                },
            ) { Text(stringResource(android.R.string.ok)) }
        },
    ) {
        if (kind == WidgetKind.TASKS) {
            GroupedSectionHeader(stringResource(R.string.widget_config_smart_lists))
            WIDGET_SMART_LISTS.forEachIndexed { index, smart ->
                val isSelected = smart == selectedSmart
                GroupedRow(
                    title = stringResource(smartListLabel(smart)),
                    position = positionOf(index, WIDGET_SMART_LISTS.size),
                    selected = isSelected,
                    trailing = if (isSelected) ({ SelectedCheck() }) else null,
                    onClick = { selectedSmart = smart; selectedFilter = null; selectedLists = emptySet() },
                )
            }
            if (savedFilters.isNotEmpty()) {
                GroupedSectionHeader(stringResource(R.string.filters_header))
                savedFilters.forEachIndexed { index, filter ->
                    val isSelected = filter.id == selectedFilter
                    GroupedRow(
                        title = filter.name,
                        position = positionOf(index, savedFilters.size),
                        selected = isSelected,
                        trailing = if (isSelected) ({ SelectedCheck() }) else null,
                        onClick = { selectedFilter = filter.id; selectedSmart = null; selectedLists = emptySet() },
                    )
                }
            }
            if (lists.isNotEmpty()) {
                GroupedSectionHeader(stringResource(R.string.lists_header))
                lists.forEachIndexed { index, list ->
                    val isSelected = list.id in selectedLists
                    GroupedRow(
                        title = list.name,
                        position = positionOf(index, lists.size),
                        selected = isSelected,
                        leading = { Icon(Icons.Rounded.Circle, contentDescription = null, tint = pastelize(list.color, dark)) },
                        trailing = if (isSelected) ({ SelectedCheck() }) else null,
                        onClick = {
                            selectedSmart = null
                            selectedFilter = null
                            selectedLists = if (isSelected) selectedLists - list.id else selectedLists + list.id
                        },
                    )
                }
                if (selectedLists.size > 1) {
                    Spacer(Modifier.height(24.dp))
                    GroupedRow(
                        title = stringResource(R.string.widget_config_group_by_list),
                        summary = stringResource(R.string.widget_config_group_by_list_hint),
                        position = Position.Alone,
                        trailing = { Switch(checked = groupByList, onCheckedChange = onGroupByListChange) },
                        onClick = { onGroupByListChange(!groupByList) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        } else if (kind == WidgetKind.LISTS && lists.isNotEmpty()) {
            GroupedSectionHeader(stringResource(R.string.lists_header))
            lists.forEachIndexed { index, list ->
                // `null` means "every list" (the default) until the user narrows it down.
                val isSelected = selectedListIds?.let { list.id in it } ?: true
                GroupedRow(
                    title = list.name,
                    position = positionOf(index, lists.size),
                    selected = isSelected,
                    leading = { Icon(Icons.Rounded.Circle, contentDescription = null, tint = pastelize(list.color, dark)) },
                    trailing = if (isSelected) ({ SelectedCheck() }) else null,
                    onClick = {
                        val current = selectedListIds ?: lists.map { it.id }.toSet()
                        onSelectedListIdsChange(if (isSelected) current - list.id else current + list.id)
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        } else if (kind == WidgetKind.UP_NEXT && lists.isNotEmpty()) {
            // Single choice: `null` is every list, otherwise a one-element set.
            val chosen = selectedListIds?.singleOrNull()
            GroupedSectionHeader(stringResource(R.string.lists_header))
            GroupedRow(
                title = stringResource(R.string.widget_up_next_any_list),
                position = positionOf(0, lists.size + 1),
                selected = chosen == null,
                trailing = if (chosen == null) ({ SelectedCheck() }) else null,
                onClick = { onSelectedListIdsChange(null) },
            )
            lists.forEachIndexed { index, list ->
                val isSelected = list.id == chosen
                GroupedRow(
                    title = list.name,
                    position = positionOf(index + 1, lists.size + 1),
                    selected = isSelected,
                    leading = { Icon(Icons.Rounded.Circle, contentDescription = null, tint = pastelize(list.color, dark)) },
                    trailing = if (isSelected) ({ SelectedCheck() }) else null,
                    onClick = { onSelectedListIdsChange(setOf(list.id)) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        GroupedRow(
            title = stringResource(R.string.widget_config_background),
            summary = stringResource(backgroundLabel(background)),
            position = Position.Top,
            onClick = { showBackgroundPicker = true },
        )
        if (kind == WidgetKind.TASKS) {
            GroupedRow(
                title = stringResource(R.string.widget_config_theme),
                summary = stringResource(themeOverrideLabel(themeOverride)),
                position = Position.Middle,
                onClick = { showThemePicker = true },
            )
            GroupedRow(
                title = stringResource(R.string.widget_config_row_color),
                summary = stringResource(rowColorLabel(rowColor)),
                position = Position.Bottom,
                onClick = { showRowColorPicker = true },
            )
        } else {
            GroupedRow(
                title = stringResource(R.string.widget_config_theme),
                summary = stringResource(themeOverrideLabel(themeOverride)),
                position = Position.Bottom,
                onClick = { showThemePicker = true },
            )
        }
    }

    if (showBackgroundPicker) {
        OptionPicker(
            title = stringResource(R.string.widget_config_background),
            options = kind.backgrounds(),
            selected = background,
            label = { stringResource(backgroundLabel(it)) },
            summary = { stringResource(backgroundHint(it)) },
            onSelect = onBackgroundChange,
            onDismiss = { showBackgroundPicker = false },
        )
    }
    if (showThemePicker) {
        OptionPicker(
            title = stringResource(R.string.widget_config_theme),
            options = listOf<WidgetThemeMode?>(null, WidgetThemeMode.LIGHT, WidgetThemeMode.DARK),
            selected = themeOverride,
            label = { stringResource(themeOverrideLabel(it)) },
            onSelect = onThemeOverrideChange,
            onDismiss = { showThemePicker = false },
        )
    }
    if (showRowColorPicker) {
        OptionPicker(
            title = stringResource(R.string.widget_config_row_color),
            options = WidgetRowColor.entries,
            selected = rowColor,
            label = { stringResource(rowColorLabel(it)) },
            summary = { stringResource(rowColorHint(it)) },
            onSelect = onRowColorChange,
            onDismiss = { showRowColorPicker = false },
        )
    }
}

internal fun smartListLabel(list: SmartList): Int = when (list) {
    SmartList.TODAY -> R.string.smart_today
    SmartList.UPCOMING -> R.string.smart_upcoming
    SmartList.OVERDUE -> R.string.smart_overdue
    else -> R.string.smart_all
}

private fun backgroundLabel(background: WidgetBackground): Int = when (background) {
    WidgetBackground.SOLID -> R.string.widget_config_background_solid
    WidgetBackground.TINTED -> R.string.widget_config_background_tinted
    WidgetBackground.TRANSPARENT -> R.string.widget_config_background_transparent
}

private fun backgroundHint(background: WidgetBackground): Int = when (background) {
    WidgetBackground.SOLID -> R.string.widget_config_background_solid_hint
    WidgetBackground.TINTED -> R.string.widget_config_background_tinted_hint
    WidgetBackground.TRANSPARENT -> R.string.widget_config_background_transparent_hint
}

private fun themeOverrideLabel(mode: WidgetThemeMode?): Int = when (mode) {
    null -> R.string.widget_config_theme_follow_app
    WidgetThemeMode.LIGHT -> R.string.widget_config_theme_light
    WidgetThemeMode.DARK -> R.string.widget_config_theme_dark
    WidgetThemeMode.SYSTEM -> R.string.widget_config_theme_follow_app
}

private fun rowColorLabel(rowColor: WidgetRowColor): Int = when (rowColor) {
    WidgetRowColor.NEUTRAL -> R.string.widget_config_row_color_neutral
    WidgetRowColor.BY_LIST -> R.string.widget_config_row_color_by_list
}

private fun rowColorHint(rowColor: WidgetRowColor): Int = when (rowColor) {
    WidgetRowColor.NEUTRAL -> R.string.widget_config_row_color_neutral_hint
    WidgetRowColor.BY_LIST -> R.string.widget_config_row_color_by_list_hint
}

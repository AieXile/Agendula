package de.jeanlucmakiola.agendula.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.data.prefs.ListReminderOverride
import de.jeanlucmakiola.agendula.data.prefs.ThemeMode
import de.jeanlucmakiola.agendula.domain.TaskFormField
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.OptionPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.agendula.ui.common.ReminderLeadPicker
import de.jeanlucmakiola.floret.components.pastelize
import de.jeanlucmakiola.floret.components.positionOf
import de.jeanlucmakiola.agendula.ui.common.reminderLeadTimeLabel

/** The settings sub-screens reached from the hub's category rows. */
private enum class SettingsSection { Appearance, TaskForm, Reminders }

/**
 * Token-based accent for a leading icon chip (container / on-container pair),
 * so each accent stays correctly paired across theme, dark mode and dynamic
 * colour.
 */
private enum class ChipAccent { Neutral, Primary, Tertiary }

/**
 * Settings, structured (after Calendula) as a category hub with sliding
 * sub-screens. The hub and the sub-screens share [CollapsingScaffold] and the
 * grouped-row card system; option lists use the full-screen [OptionPicker].
 * A full-screen destination; [onBack] pops it.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }

    // Inside a sub-screen, system back (button or gesture) returns to the hub
    // rather than popping the whole Settings destination to the lists overview.
    BackHandler(enabled = section != null) { section = null }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        SettingsHub(onBack = onBack, onOpenSection = { section = it })

        SlideInSection(visible = section == SettingsSection.Appearance) {
            AppearanceScreen(state = state, viewModel = viewModel, onBack = { section = null })
        }
        SlideInSection(visible = section == SettingsSection.TaskForm) {
            TaskFormScreen(state = state, viewModel = viewModel, onBack = { section = null })
        }
        SlideInSection(visible = section == SettingsSection.Reminders) {
            RemindersScreen(state = state, viewModel = viewModel, onBack = { section = null })
        }
    }
}

@Composable
private fun SlideInSection(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) { content() }
}

// ---------------------------------------------------------------------------
// Hub
// ---------------------------------------------------------------------------

@Composable
private fun SettingsHub(
    onBack: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
) {
    CollapsingScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        Box(Modifier.padding(horizontal = 16.dp)) { AboutCard() }
        Spacer(Modifier.height(16.dp))

        GroupedRow(
            title = stringResource(R.string.settings_section_appearance),
            summary = stringResource(R.string.settings_appearance_subtitle),
            position = Position.Top,
            leading = { CategoryIcon(Icons.Default.Palette, ChipAccent.Neutral) },
            onClick = { onOpenSection(SettingsSection.Appearance) },
        )
        GroupedRow(
            title = stringResource(R.string.settings_section_task_form),
            summary = stringResource(R.string.settings_task_form_subtitle),
            position = Position.Middle,
            leading = { CategoryIcon(Icons.Default.Tune, ChipAccent.Tertiary) },
            onClick = { onOpenSection(SettingsSection.TaskForm) },
        )
        GroupedRow(
            title = stringResource(R.string.settings_section_reminders),
            summary = stringResource(R.string.settings_reminders_subtitle),
            position = Position.Middle,
            leading = { CategoryIcon(Icons.Default.Notifications, ChipAccent.Primary) },
            onClick = { onOpenSection(SettingsSection.Reminders) },
        )
        LanguageRow(position = Position.Middle)
        ReportProblemRow(position = Position.Bottom)

        AppVersionText()
    }
}

/** App-language picker row; setting a locale recreates the activity. */
@Composable
private fun LanguageRow(position: Position) {
    val context = LocalContext.current
    // Mirror the choice locally so the row updates instantly, before recreation.
    var current by remember { mutableStateOf(AppLanguage.currentTag()) }
    var showDialog by remember { mutableStateOf(false) }
    // null = follow the system; the rest are BCP-47 tags from locales_config.xml.
    val options = remember { listOf<String?>(null) + AppLanguage.supportedTags(context) }

    GroupedRow(
        title = stringResource(R.string.settings_language),
        summary = languageLabel(current),
        position = position,
        leading = { CategoryIcon(Icons.Default.Language, ChipAccent.Neutral) },
        onClick = { showDialog = true },
    )

    if (showDialog) {
        OptionPicker(
            title = stringResource(R.string.settings_language),
            options = options,
            selected = current,
            label = { languageLabel(it) },
            onSelect = {
                current = it
                AppLanguage.apply(it)
            },
            onDismiss = { showDialog = false },
        )
    }
}

/** Opens the project's issue tracker; no data leaves the device until submitted. */
@Composable
private fun ReportProblemRow(position: Position) {
    val context = LocalContext.current
    val issueUrl = stringResource(R.string.report_issue_url)
    GroupedRow(
        title = stringResource(R.string.settings_report_problem),
        summary = stringResource(R.string.settings_report_problem_hint),
        position = position,
        leading = { CategoryIcon(Icons.Default.BugReport, ChipAccent.Neutral) },
        onClick = { openUrl(context, issueUrl) },
    )
}

@Composable
private fun languageLabel(tag: String?): String =
    if (tag == null) stringResource(R.string.settings_language_auto) else AppLanguage.displayName(tag)

// ---------------------------------------------------------------------------
// Sub-screens
// ---------------------------------------------------------------------------

@Composable
private fun AppearanceScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var showTheme by remember { mutableStateOf(false) }

    CollapsingScaffold(title = stringResource(R.string.settings_section_appearance), onBack = onBack) {
        GroupedRow(
            title = stringResource(R.string.settings_theme),
            summary = stringResource(themeLabel(state.settings.themeMode)),
            position = Position.Top,
            onClick = { showTheme = true },
        )
        GroupedRow(
            title = stringResource(R.string.settings_dynamic_color),
            summary = if (dynamicColorAvailable) {
                stringResource(R.string.settings_dynamic_color_hint)
            } else {
                stringResource(R.string.settings_dynamic_color_unavailable)
            },
            position = Position.Bottom,
            trailing = {
                Switch(
                    checked = state.settings.dynamicColor && dynamicColorAvailable,
                    onCheckedChange = viewModel::setDynamicColor,
                    enabled = dynamicColorAvailable,
                )
            },
            onClick = if (dynamicColorAvailable) {
                { viewModel.setDynamicColor(!state.settings.dynamicColor) }
            } else {
                null
            },
        )
    }

    if (showTheme) {
        OptionPicker(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries,
            selected = state.settings.themeMode,
            label = { stringResource(themeLabel(it)) },
            onSelect = viewModel::setThemeMode,
            onDismiss = { showTheme = false },
        )
    }
}

@Composable
private fun TaskFormScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    var showDefaultList by remember { mutableStateOf(false) }

    CollapsingScaffold(title = stringResource(R.string.settings_section_task_form), onBack = onBack) {
        Text(
            text = stringResource(R.string.settings_form_fields_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        val fields = TaskFormField.entries
        fields.forEachIndexed { index, field ->
            val checked = field in state.settings.defaultEditFields
            GroupedRow(
                title = stringResource(formFieldLabel(field)),
                position = positionOf(index, fields.size),
                leading = {
                    Icon(
                        imageVector = formFieldIcon(field),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailing = {
                    Switch(checked = checked, onCheckedChange = { viewModel.setFormFieldDefault(field, it) })
                },
                onClick = { viewModel.setFormFieldDefault(field, !checked) },
            )
        }

        Spacer(Modifier.height(24.dp))
        GroupedRow(
            title = stringResource(R.string.settings_default_list),
            summary = defaultListLabel(state),
            position = Position.Top,
            onClick = { showDefaultList = true },
        )
        GroupedRow(
            title = stringResource(R.string.settings_add_subtask_row),
            summary = stringResource(R.string.settings_add_subtask_row_hint),
            position = Position.Bottom,
            trailing = {
                Switch(
                    checked = state.settings.showAddSubtaskRow,
                    onCheckedChange = viewModel::setShowAddSubtaskRow,
                )
            },
            onClick = { viewModel.setShowAddSubtaskRow(!state.settings.showAddSubtaskRow) },
        )
    }

    if (showDefaultList) {
        val dark = isSystemInDarkTheme()
        // null = first available list; the rest are real list ids.
        val options = remember(state.lists) { listOf<Long?>(null) + state.lists.map { it.id } }
        OptionPicker(
            title = stringResource(R.string.settings_default_list),
            options = options,
            selected = state.settings.defaultListId,
            label = { id -> state.lists.firstOrNull { it.id == id }?.name ?: stringResource(R.string.settings_default_list_first) },
            leading = { id ->
                state.lists.firstOrNull { it.id == id }?.let { list ->
                    Icon(Icons.Rounded.Circle, contentDescription = null, tint = pastelize(list.color, dark))
                }
            },
            onSelect = { viewModel.setDefaultList(it) },
            onDismiss = { showDefaultList = false },
        )
    }
}

@Composable
private fun RemindersScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* pref already set; a denial just leaves the OS gate shut */ }
    val toggleReminders: (Boolean) -> Unit = { enabled ->
        viewModel.setRemindersEnabled(enabled)
        if (enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val canExact = rememberExactAlarmAllowed(context)
    val exactAlarmRow = showExactAlarmRow()
    val dark = isSystemInDarkTheme()
    var showOffset by remember { mutableStateOf(false) }
    var perListExpanded by remember { mutableStateOf(false) }
    var listPickerId by remember { mutableStateOf<Long?>(null) }
    val lists = state.lists.filter { it.id > 0L }

    CollapsingScaffold(title = stringResource(R.string.settings_section_reminders), onBack = onBack) {
        GroupedRow(
            title = stringResource(R.string.settings_reminders),
            summary = stringResource(R.string.settings_reminders_hint),
            position = Position.Top,
            trailing = { Switch(checked = state.settings.remindersEnabled, onCheckedChange = toggleReminders) },
            onClick = { toggleReminders(!state.settings.remindersEnabled) },
        )
        GroupedRow(
            title = stringResource(R.string.settings_default_reminder),
            summary = reminderLeadTimeLabel(state.settings.reminderLeadMinutes),
            position = if (exactAlarmRow) Position.Middle else Position.Bottom,
            onClick = { showOffset = true },
        )
        if (exactAlarmRow) {
            GroupedRow(
                title = stringResource(R.string.settings_exact_alarms),
                summary = stringResource(
                    if (canExact) R.string.settings_exact_alarms_allowed
                    else R.string.settings_exact_alarms_blocked,
                ),
                position = Position.Bottom,
                onClick = if (canExact) null else ({ context.openExactAlarmSettings() }),
            )
        }

        // Per-list overrides: the whole section folds behind one header. Each
        // list may keep, drop, or replace the global default for its tasks.
        if (lists.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            // The header and the list rows form one connected run: the header
            // opens its bottom (Top) when expanded, and the last list rounds it off.
            GroupedRow(
                title = stringResource(R.string.settings_list_reminders_title),
                summary = stringResource(R.string.settings_list_reminders_hint),
                position = if (perListExpanded) Position.Top else Position.Alone,
                trailing = {
                    Icon(
                        imageVector = if (perListExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { perListExpanded = !perListExpanded },
            )
            AnimatedVisibility(
                visible = perListExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    lists.forEachIndexed { index, list ->
                        GroupedRow(
                            title = list.name,
                            summary = listOverrideSummary(
                                listOverrideChoice(state, list.id),
                                state.settings.reminderLeadMinutes,
                            ),
                            // Every list is mid-run under the header; the last rounds off.
                            position = if (index == lists.lastIndex) Position.Bottom else Position.Middle,
                            leading = {
                                Icon(Icons.Rounded.Circle, contentDescription = null, tint = pastelize(list.color, dark))
                            },
                            onClick = { listPickerId = list.id },
                        )
                    }
                }
            }
        }
    }

    if (showOffset) {
        ReminderLeadPicker(
            title = stringResource(R.string.settings_default_reminder),
            selected = ListReminderOverride.Minutes(state.settings.reminderLeadMinutes),
            allowInherit = false,
            allowNone = false,
            onSelect = { if (it is ListReminderOverride.Minutes) viewModel.setReminderLeadMinutes(it.minutes) },
            onDismiss = { showOffset = false },
        )
    }
    listPickerId?.let { id ->
        ReminderLeadPicker(
            title = state.lists.firstOrNull { it.id == id }?.name
                ?: stringResource(R.string.settings_default_reminder),
            selected = listOverrideChoice(state, id),
            allowInherit = true,
            allowNone = true,
            onSelect = { viewModel.setListReminderOverride(id, it) },
            onDismiss = { listPickerId = null },
        )
    }
}

/** The stored override for [listId], as a picker choice (absent → inherit). */
private fun listOverrideChoice(state: SettingsUiState, listId: Long): ListReminderOverride {
    val map = state.settings.perListReminderOverride
    return when {
        !map.containsKey(listId) -> ListReminderOverride.Inherit
        map[listId] == null -> ListReminderOverride.None
        else -> ListReminderOverride.Minutes(map.getValue(listId)!!)
    }
}

/** Row summary for a list: its override, or the inherited global default. */
@Composable
private fun listOverrideSummary(choice: ListReminderOverride, globalDefault: Int): String = when (choice) {
    ListReminderOverride.Inherit ->
        stringResource(R.string.settings_list_reminder_inherits, reminderLeadTimeLabel(globalDefault))
    ListReminderOverride.None -> stringResource(R.string.reminder_none)
    is ListReminderOverride.Minutes -> reminderLeadTimeLabel(choice.minutes)
}

// ---------------------------------------------------------------------------
// About + shared building blocks
// ---------------------------------------------------------------------------

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val sourceUrl = stringResource(R.string.about_source_url)
    val licenseUrl = stringResource(R.string.about_license_url)
    val supportUrl = stringResource(R.string.about_support_url)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLogo()
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = stringResource(R.string.settings_about_author),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { openUrl(context, sourceUrl) },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_about_source))
                }
                OutlinedButton(
                    onClick = { openUrl(context, licenseUrl) },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Gavel, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_license))
                }
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { openUrl(context, supportUrl) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_about_support))
            }
        }
    }
}

@Composable
private fun AppLogo() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colorResource(R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = stringResource(R.string.settings_about_logo_desc),
            modifier = Modifier.requiredSize(108.dp),
        )
    }
}

@Composable
private fun AppVersionText() {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "—"
    }
    Text(
        text = stringResource(R.string.settings_about_version, versionName),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    )
}

/** Leading circular icon chip; colours come from the M3 scheme via a token pair. */
@Composable
private fun CategoryIcon(icon: ImageVector, accent: ChipAccent) {
    val scheme = MaterialTheme.colorScheme
    val (background, iconColor) = when (accent) {
        ChipAccent.Neutral -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        ChipAccent.Primary -> scheme.primaryContainer to scheme.onPrimaryContainer
        ChipAccent.Tertiary -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun defaultListLabel(state: SettingsUiState): String =
    state.lists.firstOrNull { it.id == state.settings.defaultListId }?.name
        ?: stringResource(R.string.settings_default_list_first)

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

private fun formFieldLabel(field: TaskFormField): Int = when (field) {
    TaskFormField.Description -> R.string.field_description
    TaskFormField.Priority -> R.string.edit_priority_label
    TaskFormField.Progress -> R.string.edit_progress_label
    TaskFormField.Parent -> R.string.edit_parent_label
    TaskFormField.Reminder -> R.string.edit_reminder_label
}

private fun formFieldIcon(field: TaskFormField): ImageVector = when (field) {
    TaskFormField.Description -> Icons.AutoMirrored.Rounded.Notes
    TaskFormField.Priority -> Icons.Rounded.Flag
    TaskFormField.Progress -> Icons.Rounded.Percent
    TaskFormField.Parent -> Icons.Rounded.AccountTree
    TaskFormField.Reminder -> Icons.Default.Notifications
}

/**
 * Exact-alarm control is only meaningful on API 31-32 (SCHEDULE_EXACT_ALARM,
 * revocable). On 33+ the app holds USE_EXACT_ALARM (always granted), so the row
 * would be a permanent no-op — hide it there.
 */
private fun showExactAlarmRow(): Boolean =
    Build.VERSION.SDK_INT in Build.VERSION_CODES.S..Build.VERSION_CODES.S_V2

@Composable
private fun rememberExactAlarmAllowed(context: Context): Boolean {
    var allowed by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        )
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                allowed = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    return allowed
}

private fun Context.openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData("package:$packageName".toUri()),
            )
        }
    }
}

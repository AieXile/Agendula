package com.aiexile.agendula.ui.settings

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.aiexile.agendula.R
import com.aiexile.agendula.data.prefs.SNOOZE_PRESETS
import com.aiexile.agendula.data.reminders.TaskNotifier
import com.aiexile.agendula.domain.TaskReminderOrder
import com.aiexile.agendula.domain.asTaskReminder
import com.aiexile.agendula.ui.common.LocalUse24HourFormat
import com.aiexile.agendula.ui.common.OnResume
import com.aiexile.agendula.ui.common.canScheduleExactAlarms
import com.aiexile.agendula.ui.common.openExactAlarmSettings
import com.aiexile.agendula.ui.common.ReminderDefaultPicker
import com.aiexile.agendula.ui.common.TimePickerAlert
import com.aiexile.agendula.ui.common.formatMinuteOfDay
import com.aiexile.agendula.ui.common.reminderLabel
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.OptionPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.components.pastelize
import de.jeanlucmakiola.floret.identity.collapseExit
import de.jeanlucmakiola.floret.identity.expandEnter
import de.jeanlucmakiola.floret.reminders.ReminderLead
import de.jeanlucmakiola.floret.reminders.ReminderOverride
import de.jeanlucmakiola.floret.reminders.reminderOverrideFor
import java.time.LocalTime

/** Whether due tasks notify at all, how far ahead, and which lists differ. */
@Composable
internal fun NotificationsScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    visible: Boolean,
) {
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(context.notificationsAllowed()) }
    OnResume { notificationsAllowed = context.notificationsAllowed() }
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // Pref already set; a denial just leaves the OS gate shut, and the warning row says so.
        notificationsAllowed = context.notificationsAllowed()
    }
    // From the warning row: a refusal the system no longer asks about can only be
    // undone in the app's notification settings.
    val fixLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsAllowed = context.notificationsAllowed()
        val activity = context as? Activity
        if (!granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            context.openNotificationSettings()
        }
    }
    val fixNotifications = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.hasNotificationPermission()) {
            fixLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.openNotificationSettings()
        }
    }
    val toggleReminders: (Boolean) -> Unit = { enabled ->
        viewModel.setRemindersEnabled(enabled)
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.hasNotificationPermission()) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val canExact = rememberExactAlarmAllowed(context)
    val exactAlarmRow = showExactAlarmRow()
    val dark = isSystemInDarkTheme()
    var showOffset by remember { mutableStateOf(false) }
    var showAllDay by remember { mutableStateOf(false) }
    var showSnooze by remember { mutableStateOf(false) }
    var showAllDayTime by remember { mutableStateOf(false) }
    var perListExpanded by remember { mutableStateOf(false) }
    var expandedLists by remember { mutableStateOf(emptySet<Long>()) }
    var overrideTarget by remember { mutableStateOf<OverrideTarget?>(null) }
    val allDayFiresAt = stringResource(
        R.string.settings_allday_reminder_fires_at,
        formatMinuteOfDay(state.settings.allDayReminderMinuteOfDay, LocalUse24HourFormat.current),
    )
    val lists = state.lists.filter { it.id > 0L }

    CollapsingScaffold(
        title = stringResource(R.string.settings_section_reminders),
        onBack = onBack,
        predictiveBack = true,
        visible = visible,
    ) {
        if (state.settings.remindersEnabled && !notificationsAllowed) {
            GroupedRow(
                title = stringResource(R.string.settings_notifications_blocked),
                summary = stringResource(R.string.settings_notifications_blocked_hint),
                position = Position.Alone,
                container = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                leading = { Icon(Icons.Rounded.NotificationsOff, contentDescription = null) },
                onClick = fixNotifications,
            )
            Spacer(Modifier.height(24.dp))
        }
        GroupedRow(
            title = stringResource(R.string.settings_reminders),
            summary = stringResource(R.string.settings_reminders_hint),
            position = Position.Top,
            trailing = { Switch(checked = state.settings.remindersEnabled, onCheckedChange = toggleReminders) },
            onClick = { toggleReminders(!state.settings.remindersEnabled) },
        )
        if (exactAlarmRow) {
            GroupedRow(
                title = stringResource(R.string.settings_exact_alarms),
                summary = stringResource(
                    if (canExact) R.string.settings_exact_alarms_allowed
                    else R.string.settings_exact_alarms_blocked,
                ),
                position = Position.Middle,
                trailing = if (canExact) ({ SelectedCheck() }) else null,
                onClick = { context.openExactAlarmSettings() },
            )
        }
        GroupedRow(
            title = stringResource(R.string.settings_default_reminder),
            summary = reminderChoiceLabel(state.settings.defaultReminders),
            position = Position.Middle,
            onClick = { showOffset = true },
        )
        GroupedRow(
            title = stringResource(R.string.settings_default_reminder_allday),
            summary = reminderChoiceLabel(state.settings.defaultAllDayReminders, allDay = true),
            position = Position.Middle,
            onClick = { showAllDay = true },
        )
        GroupedRow(
            title = stringResource(R.string.settings_allday_reminder_time),
            summary = stringResource(
                R.string.settings_allday_reminder_time_summary,
                formatMinuteOfDay(state.settings.allDayReminderMinuteOfDay, LocalUse24HourFormat.current),
            ),
            position = Position.Bottom,
            onClick = { showAllDayTime = true },
        )

        Spacer(Modifier.height(24.dp))
        val batteryExempt = rememberBatteryOptimizationExempt(context)
        GroupedRow(
            title = stringResource(R.string.settings_reliable_delivery),
            summary = stringResource(
                if (batteryExempt) R.string.settings_reliable_delivery_exempt
                else R.string.settings_reliable_delivery_hint,
            ),
            position = Position.Top,
            trailing = if (batteryExempt) ({ SelectedCheck() }) else null,
            onClick = { context.openBatteryOptimizationSettings() },
        )
        GroupedRow(
            title = stringResource(R.string.settings_snooze_duration),
            summary = snoozeLabel(state.settings.snoozeMinutes),
            position = Position.Bottom,
            onClick = { showSnooze = true },
        )

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
                enter = expandEnter(),
                exit = collapseExit(),
            ) {
                Column {
                    lists.forEachIndexed { index, list ->
                        val expanded = list.id in expandedLists
                        val last = index == lists.lastIndex
                        // Every list is mid-run under the header; the last rounds
                        // off, unless it is open and its two rows close the run.
                        GroupedRow(
                            title = list.name,
                            position = if (last && !expanded) Position.Bottom else Position.Middle,
                            leading = {
                                Icon(Icons.Rounded.Circle, contentDescription = null, tint = pastelize(list.color, dark))
                            },
                            trailing = {
                                Icon(
                                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = {
                                expandedLists = if (expanded) expandedLists - list.id else expandedLists + list.id
                            },
                        )
                        AnimatedVisibility(visible = expanded, enter = expandEnter(), exit = collapseExit()) {
                            Column {
                                GroupedRow(
                                    title = stringResource(R.string.settings_default_reminder),
                                    summary = listOverrideSummary(
                                        state.settings.perListReminderOverride.reminderOverrideFor(list.id),
                                        state.settings.defaultReminders,
                                        allDay = false,
                                    ),
                                    position = Position.Middle,
                                    onClick = { overrideTarget = OverrideTarget(list.id, allDay = false) },
                                )
                                GroupedRow(
                                    title = stringResource(R.string.settings_default_reminder_allday),
                                    summary = listOverrideSummary(
                                        state.settings.perListAllDayReminderOverride.reminderOverrideFor(list.id),
                                        state.settings.defaultAllDayReminders,
                                        allDay = true,
                                    ),
                                    position = Position.Middle,
                                    onClick = { overrideTarget = OverrideTarget(list.id, allDay = true) },
                                )
                                GroupedRow(
                                    title = stringResource(R.string.settings_list_notifications),
                                    summary = stringResource(R.string.settings_list_notifications_hint),
                                    position = if (last) Position.Bottom else Position.Middle,
                                    onClick = { context.openChannelSettings(viewModel.listChannelId(list)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOffset) {
        ReminderDefaultPicker(
            title = stringResource(R.string.settings_default_reminder),
            selected = state.settings.defaultReminders.let {
                if (it.isEmpty()) ReminderOverride.None else ReminderOverride.Minutes.of(it)
            },
            allowInherit = false,
            onSelect = { viewModel.setDefaultReminders((it as? ReminderOverride.Minutes)?.leads.orEmpty()) },
            onDismiss = { showOffset = false },
        )
    }
    if (showAllDay) {
        ReminderDefaultPicker(
            title = stringResource(R.string.settings_default_reminder_allday),
            selected = state.settings.defaultAllDayReminders.let {
                if (it.isEmpty()) ReminderOverride.None else ReminderOverride.Minutes.of(it)
            },
            allowInherit = false,
            allDay = true,
            leadTimeSummary = allDayFiresAt,
            onSelect = { viewModel.setDefaultAllDayReminders((it as? ReminderOverride.Minutes)?.leads.orEmpty()) },
            onDismiss = { showAllDay = false },
        )
    }
    if (showSnooze) {
        OptionPicker(
            title = stringResource(R.string.settings_snooze_duration),
            options = SNOOZE_PRESETS,
            selected = state.settings.snoozeMinutes,
            label = { snoozeLabel(it) },
            onSelect = viewModel::setSnoozeMinutes,
            onDismiss = { showSnooze = false },
        )
    }
    if (showAllDayTime) {
        val minute = state.settings.allDayReminderMinuteOfDay
        TimePickerAlert(
            initial = LocalTime.of(minute / 60, minute % 60),
            onConfirm = {
                viewModel.setAllDayReminderMinuteOfDay(it.hour * 60 + it.minute)
                showAllDayTime = false
            },
            onDismiss = { showAllDayTime = false },
        )
    }
    overrideTarget?.let { target ->
        val overrides = if (target.allDay) state.settings.perListAllDayReminderOverride
        else state.settings.perListReminderOverride
        ReminderDefaultPicker(
            title = state.lists.firstOrNull { it.id == target.listId }?.name
                ?: stringResource(R.string.settings_default_reminder),
            selected = overrides.reminderOverrideFor(target.listId),
            allowInherit = true,
            allDay = target.allDay,
            leadTimeSummary = if (target.allDay) allDayFiresAt else null,
            onSelect = {
                if (target.allDay) viewModel.setListAllDayReminderOverride(target.listId, it)
                else viewModel.setListReminderOverride(target.listId, it)
            },
            onDismiss = { overrideTarget = null },
        )
    }
}

/** "10 minutes", "1 hour". */
@Composable
private fun snoozeLabel(minutes: Int): String =
    if (minutes % 60 == 0) pluralStringResource(R.plurals.duration_hours, minutes / 60, minutes / 60)
    else pluralStringResource(R.plurals.duration_minutes, minutes, minutes)

/** Which list's override picker is open, and for which kind of task. */
private data class OverrideTarget(val listId: Long, val allDay: Boolean)

/** Row summary for a list: its override, or the inherited global default. */
@Composable
private fun listOverrideSummary(choice: ReminderOverride, globalDefault: List<ReminderLead>, allDay: Boolean): String =
    when (choice) {
        ReminderOverride.Inherit ->
            stringResource(R.string.settings_list_reminder_inherits, reminderChoiceLabel(globalDefault, allDay))
        ReminderOverride.None -> stringResource(R.string.reminder_none)
        is ReminderOverride.Minutes -> reminderChoiceLabel(choice.leads, allDay)
    }

/**
 * "30 minutes before, At due time", or "No reminder" for an empty set. Each
 * names its date once any counts from the start.
 */
@Composable
private fun reminderChoiceLabel(leads: List<ReminderLead>, allDay: Boolean = false): String {
    if (leads.isEmpty()) return stringResource(R.string.reminder_none)
    val reminders = leads.map { it.asTaskReminder() }.sortedWith(TaskReminderOrder)
    val anchored = reminders.any { it.fromStart }
    return reminders.map { reminder ->
        reminderLabel(reminder.minutesBefore, allDay, reminder.fromStart, anchored)
    }.joinToString(", ")
}

/** SCHEDULE_EXACT_ALARM is user-granted from API 31; below that exact alarms need no grant. */
private fun showExactAlarmRow(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
private fun rememberExactAlarmAllowed(context: Context): Boolean {
    var allowed by remember { mutableStateOf(context.canScheduleExactAlarms()) }
    OnResume { allowed = context.canScheduleExactAlarms() }
    return allowed
}

@Composable
private fun rememberBatteryOptimizationExempt(context: Context): Boolean {
    var exempt by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    OnResume { exempt = context.isIgnoringBatteryOptimizations() }
    return exempt
}

private fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

/**
 * The system's optimisation list, falling back to the app's own info page. Not the direct
 * exemption dialog: that needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Play restricts.
 */
private fun Context.openBatteryOptimizationSettings() {
    if (runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.isFailure) {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
    }
}

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/** Whether a reminder can actually reach the user: permission, app switch and reminder channels. */
private fun Context.notificationsAllowed(): Boolean {
    if (!hasNotificationPermission() || !NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
    return TaskNotifier.remindersAudible(getSystemService(NotificationManager::class.java))
}

private fun Context.openNotificationSettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
}

private fun Context.openChannelSettings(channelId: String) {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
        )
    }
}

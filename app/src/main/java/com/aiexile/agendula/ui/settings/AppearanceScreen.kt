package com.aiexile.agendula.ui.settings

import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiexile.agendula.R
import com.aiexile.agendula.data.prefs.ThemeMode
import com.aiexile.agendula.data.prefs.TimeFormatPref
import com.aiexile.agendula.data.prefs.is24Hour
import com.aiexile.agendula.ui.common.formatMinuteOfDay
import com.aiexile.agendula.ui.common.localeFirstDayOfWeek
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.OptionPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.locale.currentLocale
import java.time.DayOfWeek
import java.time.format.TextStyle

/** How the app looks: the theme, wallpaper colours, and how times and weeks are written. */
@Composable
internal fun AppearanceScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    visible: Boolean,
) {
    val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var showTheme by remember { mutableStateOf(false) }
    var showTimeFormat by remember { mutableStateOf(false) }
    var showWeekStart by remember { mutableStateOf(false) }
    val systemIs24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val locale = currentLocale()

    CollapsingScaffold(
        title = stringResource(R.string.settings_section_appearance),
        onBack = onBack,
        predictiveBack = true,
        visible = visible,
    ) {
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

        Spacer(Modifier.height(24.dp))
        GroupedRow(
            title = stringResource(R.string.settings_time_format),
            summary = timeFormatLabel(state.settings.timeFormat),
            position = Position.Top,
            onClick = { showTimeFormat = true },
        )
        GroupedRow(
            title = stringResource(R.string.settings_week_start),
            summary = weekStartLabel(state.settings.weekStart),
            position = Position.Bottom,
            onClick = { showWeekStart = true },
        )
    }

    if (showTimeFormat) {
        OptionPicker(
            title = stringResource(R.string.settings_time_format),
            options = TimeFormatPref.entries,
            selected = state.settings.timeFormat,
            label = { timeFormatLabel(it) },
            summary = { pref ->
                if (pref == TimeFormatPref.AUTO) {
                    stringResource(
                        R.string.settings_time_format_auto_summary,
                        formatMinuteOfDay(SAMPLE_MINUTE_OF_DAY, pref.is24Hour(systemIs24Hour), locale),
                    )
                } else {
                    null
                }
            },
            onSelect = viewModel::setTimeFormat,
            onDismiss = { showTimeFormat = false },
        )
    }

    if (showWeekStart) {
        OptionPicker(
            title = stringResource(R.string.settings_week_start),
            options = listOf<DayOfWeek?>(null) + DayOfWeek.entries,
            selected = state.settings.weekStart,
            label = { weekStartLabel(it) },
            summary = { day ->
                if (day == null) {
                    stringResource(
                        R.string.settings_week_start_auto_summary,
                        localeFirstDayOfWeek(locale).getDisplayName(TextStyle.FULL, locale),
                    )
                } else {
                    null
                }
            },
            onSelect = viewModel::setWeekStart,
            onDismiss = { showWeekStart = false },
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
private fun timeFormatLabel(pref: TimeFormatPref): String = stringResource(
    when (pref) {
        TimeFormatPref.AUTO -> R.string.settings_time_format_auto
        TimeFormatPref.TWELVE_HOUR -> R.string.settings_time_format_12h
        TimeFormatPref.TWENTY_FOUR_HOUR -> R.string.settings_time_format_24h
    },
)

/** "Automatic", or the localised weekday name. */
@Composable
private fun weekStartLabel(day: DayOfWeek?): String =
    day?.getDisplayName(TextStyle.FULL, currentLocale()) ?: stringResource(R.string.settings_week_start_auto)

/** 14:00, the sample time "Automatic" is shown with. */
private const val SAMPLE_MINUTE_OF_DAY = 14 * 60

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

package com.aiexile.agendula.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.aiexile.agendula.MainActivity
import com.aiexile.agendula.R
import com.aiexile.agendula.domain.SmartList
import com.aiexile.agendula.ui.theme.AgendulaDarkFallback
import com.aiexile.agendula.ui.theme.AgendulaLightFallback
import de.jeanlucmakiola.floret.glance.FloretGlanceTheme
import de.jeanlucmakiola.floret.glance.WidgetBackground
import de.jeanlucmakiola.floret.glance.WidgetMessage
import de.jeanlucmakiola.floret.glance.WidgetSurface
import de.jeanlucmakiola.floret.glance.WidgetThemeMode
import de.jeanlucmakiola.floret.glance.WidgetTile
import de.jeanlucmakiola.floret.glance.R as GlanceR
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * "Week" home-screen widget: a seven-day strip, one tile per day, each with
 * its open-task count — the one shape none of the app's other widgets or
 * screens offer (they're all "one list" or "one task" shaped).
 */
class WeekWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    // Exact, so the day count can follow the real width.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = runCatching { context.widgetStats().first() }.getOrDefault(WidgetStatsData.Unavailable)
        provideContent {
            val background = parseWidgetBackground(currentState(WIDGET_BACKGROUND_KEY))
            val themeOverride = parseWidgetThemeOverride(currentState(WIDGET_THEME_KEY))
            val data by remember { context.widgetStats() }.collectAsState(initial = initial)
            val ready = data as? WidgetStatsData.Ready ?: initial as? WidgetStatsData.Ready
            FloretGlanceTheme(
                lightScheme = AgendulaLightFallback,
                darkScheme = AgendulaDarkFallback,
                dynamicColor = ready?.snapshot?.dynamicColor ?: true,
                mode = ready?.let { effectiveThemeMode(it.snapshot.themeMode, themeOverride) } ?: WidgetThemeMode.SYSTEM,
            ) {
                WeekWidgetBody(data, background)
            }
        }
    }
}

@Composable
private fun WeekWidgetBody(data: WidgetStatsData?, background: WidgetBackground) {
    val context = LocalContext.current
    val ready = data as? WidgetStatsData.Ready
    WidgetSurface(background = background) {
        when {
            data == null -> Unit
            ready == null -> WidgetMessage(context.getString(R.string.widget_unavailable), GlanceModifier.padding(8.dp))
            else -> {
                // As many days as fit at a comfortable width, up to the week.
                // Gaps come from each cell's padding, not Spacers: Glance caps a
                // Row at 10 children.
                val days = ((LocalSize.current.width - 8.dp) / MinDayWidth).toInt().coerceIn(1, ready.snapshot.week.size)
                Row(modifier = GlanceModifier.fillMaxSize().padding(4.dp)) {
                    ready.snapshot.week.take(days).forEach { day ->
                        // Glance has no margin — padding on the tile itself lands inside its fill.
                        Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = 2.dp)) {
                            WeekDayCell(day, GlanceModifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekDayCell(day: WeekDay, modifier: GlanceModifier) {
    val context = LocalContext.current
    val today = isToday(day.date)
    val background = if (today) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.surfaceVariant
    val onBackground = if (today) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurfaceVariant
    val open = if (today) {
        actionStartActivity(MainActivity.todayIntent(context))
    } else {
        actionStartActivity(MainActivity.smartListIntent(context, SmartList.UPCOMING))
    }
    val badgeBackground = if (today) GlanceTheme.colors.primary else GlanceTheme.colors.surface
    val onBadgeBackground = if (today) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurfaceVariant
    WidgetTile(color = background, modifier = modifier, onClick = open) {
        Column(modifier = GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val locale = context.resources.configuration.locales[0] ?: Locale.ROOT
            Spacer(GlanceModifier.defaultWeight())
            Box(
                modifier = GlanceModifier
                    .size(38.dp)
                    .cornerRadius(19.dp)
                    .background(badgeBackground),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = day.date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, locale),
                        maxLines = 1,
                        style = TextStyle(color = onBadgeBackground, fontSize = 10.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                    )
                    Text(
                        text = "${day.date.dayOfMonth}",
                        style = TextStyle(color = onBadgeBackground, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    )
                }
            }
            Spacer(GlanceModifier.height(3.dp))
            // Always rendered, even at 0 — an omitted count row made every
            // other day's badge drift up or down relative to its neighbours.
            // The checklist glyph (not a single checkmark) is what reads this
            // as "N tasks" rather than an unlabelled, ambiguous number.
            val countColor = if (day.overdueCount > 0) GlanceTheme.colors.error else onBackground
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    provider = ImageProvider(GlanceR.drawable.floret_ic_widget_checklist),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(countColor),
                    modifier = GlanceModifier.size(14.dp),
                )
                Spacer(GlanceModifier.width(2.dp))
                Text(
                    text = "${day.count}",
                    style = TextStyle(color = countColor, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                )
            }
            Spacer(GlanceModifier.defaultWeight())
        }
    }
}

private fun isToday(date: LocalDate): Boolean = date == java.time.LocalDate.now()

private val MinDayWidth = 64.dp

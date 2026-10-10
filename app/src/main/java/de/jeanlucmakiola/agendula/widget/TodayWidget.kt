package de.jeanlucmakiola.agendula.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import de.jeanlucmakiola.agendula.MainActivity
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.ui.theme.AgendulaDarkFallback
import de.jeanlucmakiola.agendula.ui.theme.AgendulaLightFallback
import de.jeanlucmakiola.floret.glance.FloretGlanceTheme
import de.jeanlucmakiola.floret.glance.WidgetBackground
import de.jeanlucmakiola.floret.glance.WidgetHeader
import de.jeanlucmakiola.floret.glance.WidgetHeaderAction
import de.jeanlucmakiola.floret.glance.WidgetMessage
import de.jeanlucmakiola.floret.glance.WidgetProgressRing
import de.jeanlucmakiola.floret.glance.WidgetSurface
import de.jeanlucmakiola.floret.glance.WidgetThemeMode
import de.jeanlucmakiola.floret.glance.R as GlanceR
import kotlinx.coroutines.flow.first

/**
 * "Today" home-screen widget: how far through today you are, as a ring —
 * done ÷ (done + still open today/overdue) — with today's open tasks listed
 * below (or beside, when wide) once the widget has room for them.
 */
class TodayWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    // Exact, so the ring can grow with the real placement rather than a breakpoint.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = runCatching { context.widgetStats().first() }.getOrDefault(WidgetStatsData.Unavailable)
        provideContent {
            val background = parseWidgetBackground(currentState(WIDGET_BACKGROUND_KEY), WidgetKind.TODAY)
            val themeOverride = parseWidgetThemeOverride(currentState(WIDGET_THEME_KEY))
            val data by remember { context.widgetStats() }.collectAsState(initial = initial)
            val ready = data as? WidgetStatsData.Ready ?: initial as? WidgetStatsData.Ready
            FloretGlanceTheme(
                lightScheme = AgendulaLightFallback,
                darkScheme = AgendulaDarkFallback,
                dynamicColor = ready?.snapshot?.dynamicColor ?: true,
                mode = ready?.let { effectiveThemeMode(it.snapshot.themeMode, themeOverride) } ?: WidgetThemeMode.SYSTEM,
            ) {
                TodayWidgetBody(data, background)
            }
        }
    }
}

@Composable
private fun TodayWidgetBody(data: WidgetStatsData?, background: WidgetBackground) {
    val context = LocalContext.current
    val ready = data as? WidgetStatsData.Ready
    val size = LocalSize.current
    val contentWidth = size.width - Padding * 2
    val contentHeight = size.height - Padding * 2 - HeaderHeight
    WidgetSurface(background = background) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(Padding)) {
            WidgetHeader(
                title = context.getString(R.string.smart_today),
                titleAction = actionStartActivity(MainActivity.todayIntent(context)),
            ) {
                WidgetHeaderAction(
                    icon = ImageProvider(GlanceR.drawable.floret_ic_widget_add),
                    contentDescription = context.getString(R.string.new_task),
                    action = actionStartActivity(MainActivity.newTaskIntent(context)),
                )
            }
            Spacer(GlanceModifier.height(HeaderGap))
            when {
                data == null -> Unit
                ready == null -> WidgetMessage(context.getString(R.string.widget_unavailable))
                else -> {
                    val tasks = ready.snapshot.todayTasks
                    val openToday = actionStartActivity(MainActivity.todayIntent(context))
                    when {
                        tasks.isNotEmpty() && contentWidth >= MinSideBySideWidth -> {
                            val ring = minOf(contentHeight, contentWidth * 0.4f, MaxRing)
                            Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = GlanceModifier.width(ring).fillMaxHeight().clickable(openToday),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    TodayRing(ready, ring)
                                }
                                Spacer(GlanceModifier.width(12.dp))
                                TodayTaskList(ready, GlanceModifier.defaultWeight().fillMaxHeight())
                            }
                        }
                        tasks.isNotEmpty() && contentHeight >= MinRing + ListGap + RowHeight -> {
                            val ring = minOf(contentWidth, MaxRing, maxOf(MinRing, contentHeight - ListGap - RowHeight * 2))
                            Box(
                                modifier = GlanceModifier.fillMaxWidth().height(ring).clickable(openToday),
                                contentAlignment = Alignment.Center,
                            ) {
                                TodayRing(ready, ring)
                            }
                            Spacer(GlanceModifier.height(ListGap))
                            TodayTaskList(ready, GlanceModifier.fillMaxWidth().defaultWeight())
                        }
                        else -> {
                            val ring = minOf(contentWidth, contentHeight, MaxRing)
                            Box(
                                modifier = GlanceModifier.fillMaxSize().clickable(openToday),
                                contentAlignment = Alignment.Center,
                            ) {
                                TodayRing(ready, ring)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayTaskList(ready: WidgetStatsData.Ready, modifier: GlanceModifier) {
    val tasks = ready.snapshot.todayTasks
    LazyColumn(modifier = modifier) {
        items(tasks.size) { index ->
            // A LazyColumn item's host view isn't a linear layout on its own,
            // so the row and its gap need an explicit Column to stack.
            Column {
                AgendaTaskRow(
                    row = tasks[index],
                    use24Hour = ready.snapshot.use24Hour,
                    color = GlanceTheme.colors.surfaceVariant,
                    colored = false,
                    suppressDayLabel = true,
                )
                if (index < tasks.lastIndex) Spacer(GlanceModifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun TodayRing(ready: WidgetStatsData.Ready, ringSize: Dp) {
    val context = LocalContext.current
    val snapshot = ready.snapshot
    val total = snapshot.todayDone + snapshot.todayRemaining
    val allDone = total > 0 && snapshot.todayRemaining == 0
    val progress = if (total == 0) 0f else snapshot.todayDone.toFloat() / total
    val indicator = if (allDone) GlanceTheme.colors.tertiary else GlanceTheme.colors.primary
    // Label sizes scale with the ring so a large ring doesn't hold a tiny label.
    val scale = (ringSize / MinRing).coerceIn(1f, 2f)
    WidgetProgressRing(
        progress = progress,
        size = ringSize,
        trackColor = GlanceTheme.colors.surfaceVariant,
        indicatorColor = indicator,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = GlanceModifier.padding(horizontal = ringSize * 0.15f)) {
            if (total == 0) {
                Text(
                    text = context.getString(R.string.widget_today_nothing_due),
                    maxLines = 2,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = (11 * scale).sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            } else if (allDone) {
                Text(
                    text = context.getString(R.string.widget_today_all_done),
                    maxLines = 2,
                    style = TextStyle(
                        color = GlanceTheme.colors.tertiary,
                        fontSize = (13 * scale).sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
            } else {
                Text(
                    text = "${snapshot.todayDone}/$total",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = (18 * scale).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

private val Padding = 8.dp
private val HeaderHeight = 36.dp
private val HeaderGap = 4.dp
private val ListGap = 8.dp
private val RowHeight = 48.dp
private val MinRing = 72.dp
private val MaxRing = 160.dp
private val MinSideBySideWidth = 230.dp

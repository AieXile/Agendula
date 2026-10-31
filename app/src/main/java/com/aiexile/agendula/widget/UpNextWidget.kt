package com.aiexile.agendula.widget

import android.content.Context
import android.text.format.DateFormat
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
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
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
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import com.aiexile.agendula.MainActivity
import com.aiexile.agendula.R
import com.aiexile.agendula.ui.theme.AgendulaDarkFallback
import com.aiexile.agendula.ui.theme.AgendulaLightFallback
import de.jeanlucmakiola.floret.components.pastelize
import de.jeanlucmakiola.floret.glance.FloretGlanceTheme
import de.jeanlucmakiola.floret.glance.WidgetActionButton
import de.jeanlucmakiola.floret.glance.WidgetActionStyle
import de.jeanlucmakiola.floret.glance.WidgetBackground
import de.jeanlucmakiola.floret.glance.WidgetHeaderAction
import de.jeanlucmakiola.floret.glance.WidgetMessage
import de.jeanlucmakiola.floret.glance.WidgetShape
import de.jeanlucmakiola.floret.glance.WidgetShapedIconButton
import de.jeanlucmakiola.floret.glance.WidgetSurface
import de.jeanlucmakiola.floret.glance.WidgetThemeMode
import de.jeanlucmakiola.floret.time.formatTime
import de.jeanlucmakiola.floret.glance.R as GlanceR
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * "Up next" home-screen widget: the single open task with the nearest due
 * moment, big, with a check to complete it in place — a countdown rather
 * than a list, for someone who wants to see one thing at a time. Optionally
 * narrowed to one list; when tall, the tasks after it are listed below.
 */
class UpNextWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = runCatching { context.widgetStats().first() }.getOrDefault(WidgetStatsData.Unavailable)
        provideContent {
            val background = parseWidgetBackground(currentState(WIDGET_BACKGROUND_KEY), WidgetKind.UP_NEXT)
            val themeOverride = parseWidgetThemeOverride(currentState(WIDGET_THEME_KEY))
            val listId = parseWidgetListIds(currentState(WIDGET_LIST_IDS_KEY))?.singleOrNull()
            val data by remember { context.widgetStats() }.collectAsState(initial = initial)
            val ready = data as? WidgetStatsData.Ready ?: initial as? WidgetStatsData.Ready
            FloretGlanceTheme(
                lightScheme = AgendulaLightFallback,
                darkScheme = AgendulaDarkFallback,
                dynamicColor = ready?.snapshot?.dynamicColor ?: true,
                mode = ready?.let { effectiveThemeMode(it.snapshot.themeMode, themeOverride) } ?: WidgetThemeMode.SYSTEM,
            ) {
                UpNextWidgetBody(data, background, listId)
            }
        }
    }
}

@Composable
private fun UpNextWidgetBody(data: WidgetStatsData?, background: WidgetBackground, listId: Long?) {
    val context = LocalContext.current
    val ready = data as? WidgetStatsData.Ready
    val size = LocalSize.current
    val rows = ready?.snapshot?.upcoming?.let { all -> listId?.let { id -> all.filter { it.listId == id } } ?: all }
    val list = listId?.let { id -> ready?.snapshot?.lists?.firstOrNull { it.id == id } }
    WidgetSurface(background = background) {
        when {
            data == null -> Unit
            ready == null -> WidgetMessage(context.getString(R.string.widget_unavailable), GlanceModifier.padding(8.dp))
            rows.isNullOrEmpty() -> UpNextEmpty(listId)
            size.height >= MinTallHeight -> UpNextTall(rows, ready.snapshot, list, size.height)
            else -> UpNextCompact(rows.first(), ready.snapshot, size.width, size.height)
        }
    }
}

@Composable
private fun UpNextEmpty(listId: Long?) {
    val context = LocalContext.current
    Row(modifier = GlanceModifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        WidgetMessage(context.getString(R.string.widget_up_next_empty), GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.width(8.dp))
        WidgetActionButton(
            icon = ImageProvider(GlanceR.drawable.floret_ic_widget_add),
            contentDescription = context.getString(R.string.new_task),
            action = actionStartActivity(MainActivity.newTaskIntent(context, listId)),
            style = WidgetActionStyle.FILLED,
            size = 36.dp,
        )
    }
}

/** One row tall: a cookie-shaped check leading, then the time as the hero and the title under it. */
@Composable
private fun UpNextCompact(row: WidgetTaskRow, snapshot: WidgetStatsSnapshot, width: Dp, height: Dp) {
    val context = LocalContext.current
    val title = row.title.ifBlank { context.getString(R.string.task_untitled) }
    val now = Clock.System.now()
    val accent = if (row.isOverdue) GlanceTheme.colors.error else GlanceTheme.colors.primary
    val checkSize = (height * 0.45f).coerceIn(32.dp, 44.dp)
    val heroSize = (height.value * 0.25f).coerceIn(16f, 22f)
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompleteButton(row, title, checkSize)
        Spacer(GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight().clickable(openTask(context, row))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = upNextHero(context, row, snapshot.use24Hour),
                    maxLines = 1,
                    style = TextStyle(color = accent, fontSize = heroSize.sp, fontWeight = FontWeight.Bold),
                )
                if (width >= MinRelativeInlineWidth) {
                    Spacer(GlanceModifier.width(8.dp))
                    RowPill(row, upNextLabel(context, row, now))
                }
            }
            Text(
                text = title,
                maxLines = if (height >= 88.dp) 2 else 1,
                style = titleStyle(row, 15f),
            )
        }
    }
}

/**
 * Tall: the list as a caption with the add pill, then a big cookie check
 * beside a display-size time and the title, and — with room to spare —
 * what comes after it.
 */
@Composable
private fun UpNextTall(rows: List<WidgetTaskRow>, snapshot: WidgetStatsSnapshot, list: ListTile?, height: Dp) {
    val context = LocalContext.current
    val row = rows.first()
    val title = row.title.ifBlank { context.getString(R.string.task_untitled) }
    val now = Clock.System.now()
    val accent = if (row.isOverdue) GlanceTheme.colors.error else GlanceTheme.colors.primary
    val heroSize = (height.value * 0.15f).coerceIn(26f, 38f)
    val checkSize = (height * 0.2f).coerceIn(44.dp, 56.dp)
    val laterCount = ((height - MinTallHeight - 40.dp) / LaterRowHeight).toInt().coerceIn(0, minOf(rows.size - 1, MAX_LATER_ROWS))
    Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ListDot(row.color)
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = list?.name ?: context.getString(R.string.widget_up_next_label),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
            WidgetHeaderAction(
                icon = ImageProvider(GlanceR.drawable.floret_ic_widget_add),
                contentDescription = context.getString(R.string.new_task),
                action = actionStartActivity(MainActivity.newTaskIntent(context, list?.id)),
            )
        }
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompleteButton(row, title, checkSize)
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight().clickable(openTask(context, row))) {
                Text(
                    text = upNextHero(context, row, snapshot.use24Hour),
                    maxLines = 1,
                    style = TextStyle(color = accent, fontSize = heroSize.sp, fontWeight = FontWeight.Bold),
                )
                RowPill(row, upNextLabel(context, row, now))
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    text = title,
                    maxLines = if (laterCount > 0) 2 else 3,
                    style = titleStyle(row, 16f),
                )
            }
        }
        if (laterCount > 0) {
            Text(
                text = context.getString(R.string.widget_up_next_then),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.height(4.dp))
            Column { rows.drop(1).take(laterCount).forEach { later -> LaterRow(later, snapshot.use24Hour) } }
        }
    }
}

/** The cookie check; while [WidgetCompletions] holds the row it bursts into a filled sunny shape. */
@Composable
private fun CompleteButton(row: WidgetTaskRow, title: String, size: Dp) {
    val context = LocalContext.current
    val completing = WidgetCompletions.isCompleting(row)
    WidgetShapedIconButton(
        shape = if (completing) WidgetShape.SUNNY else WidgetShape.COOKIE,
        icon = ImageProvider(GlanceR.drawable.floret_ic_widget_check),
        contentDescription = context.getString(R.string.widget_complete_task, title),
        action = completeTask(row),
        color = when {
            completing -> GlanceTheme.colors.tertiary
            row.isOverdue -> GlanceTheme.colors.errorContainer
            else -> GlanceTheme.colors.primaryContainer
        },
        iconTint = when {
            completing -> GlanceTheme.colors.onTertiary
            row.isOverdue -> GlanceTheme.colors.onErrorContainer
            else -> GlanceTheme.colors.onPrimaryContainer
        },
        size = size,
    )
}

@Composable
private fun RelativePill(text: String, overdue: Boolean, done: Boolean = false) {
    val container = when {
        done -> GlanceTheme.colors.tertiaryContainer
        overdue -> GlanceTheme.colors.errorContainer
        else -> GlanceTheme.colors.secondaryContainer
    }
    val content = when {
        done -> GlanceTheme.colors.onTertiaryContainer
        overdue -> GlanceTheme.colors.onErrorContainer
        else -> GlanceTheme.colors.onSecondaryContainer
    }
    Box(
        modifier = GlanceModifier.cornerRadius(10.dp)
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            maxLines = 1,
            style = TextStyle(color = content, fontSize = 11.sp, fontWeight = FontWeight.Bold),
        )
    }
}

/** The relative pill's text, or "Done" while the row is being ticked off. */
@Composable
private fun RowPill(row: WidgetTaskRow, relative: String) {
    val context = LocalContext.current
    if (WidgetCompletions.isCompleting(row)) {
        RelativePill(context.getString(R.string.reminder_action_done), row.isOverdue, done = true)
    } else if (relative.isNotEmpty()) {
        RelativePill(relative, row.isOverdue)
    }
}

@Composable
private fun titleStyle(row: WidgetTaskRow, fontSize: Float): TextStyle {
    val completing = WidgetCompletions.isCompleting(row)
    return TextStyle(
        color = if (completing) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Medium,
        textDecoration = if (completing) TextDecoration.LineThrough else TextDecoration.None,
    )
}

@Composable
private fun LaterRow(row: WidgetTaskRow, use24Hour: Boolean) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(LaterRowHeight).clickable(openTask(context, row)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListDot(row.color)
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = row.title.ifBlank { context.getString(R.string.task_untitled) },
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
        )
        Text(
            text = upNextHero(context, row, use24Hour),
            maxLines = 1,
            style = TextStyle(
                color = if (row.isOverdue) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
            ),
        )
    }
}

@Composable
private fun ListDot(color: Int) {
    Box(
        modifier = GlanceModifier.size(8.dp).cornerRadius(4.dp)
            .background(ColorProvider(day = pastelize(color, false), night = pastelize(color, true))),
    ) {}
}

private fun openTask(context: Context, row: WidgetTaskRow): Action =
    actionStartActivity(MainActivity.taskIntent(context, row.taskId, row.occurrenceStart?.toEpochMilliseconds()))

private fun completeTask(row: WidgetTaskRow): Action =
    actionRunCallback<CompleteTaskAction>(
        actionParametersOf(
            CompleteTaskAction.TaskIdKey to row.taskId,
            CompleteTaskAction.OccurrenceKey to (row.occurrenceStart?.toEpochMilliseconds() ?: CompleteTaskAction.NO_OCCURRENCE),
        ),
    )

/**
 * The big line: a timed task's clock time (with its weekday once it's past
 * tomorrow), or an all-day task's day.
 */
internal fun upNextHero(context: Context, row: WidgetTaskRow, use24Hour: Boolean): String {
    val due = row.due ?: return ""
    val locale = context.resources.configuration.locales[0]
    val day = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEd"), locale)
        .format(due.toJavaInstant().atZone(ZoneId.systemDefault()))
    if (row.isAllDay) {
        return when (row.dueKind) {
            DueKind.TODAY -> context.getString(R.string.smart_today)
            DueKind.TOMORROW -> context.getString(R.string.widget_up_next_tomorrow)
            else -> day
        }
    }
    val time = due.formatTime(use24Hour)
    return if (row.dueKind == DueKind.LATER && !row.isOverdue) "$day $time" else time
}

/**
 * The relative line beside [upNextHero]: "in 3 h", "Tomorrow", "2 d overdue".
 * Coarse on purpose — this widget only redraws on data changes, at midnight,
 * and on the half-hourly `updatePeriodMillis`, so a minute-level countdown
 * would just look wrong between redraws.
 */
internal fun upNextLabel(context: Context, row: WidgetTaskRow, now: Instant): String {
    val due = row.due ?: return ""
    if (row.isAllDay && !row.isOverdue) return if (row.dueKind == DueKind.LATER) daysUntil(context, due, now) else ""
    if (row.isOverdue) {
        val hours = (now - due).inWholeHours
        return when {
            hours < 1 -> context.getString(R.string.widget_up_next_soon)
            hours < HOURS_PER_DAY -> context.resources.getQuantityString(R.plurals.widget_up_next_overdue_hours, hours.toInt(), hours.toInt())
            else -> {
                val days = hours / HOURS_PER_DAY
                context.resources.getQuantityString(R.plurals.widget_up_next_overdue_days, days.toInt(), days.toInt())
            }
        }
    }
    if (row.dueKind == DueKind.TOMORROW) return context.getString(R.string.widget_up_next_tomorrow)
    val hours = (due - now).inWholeHours
    return when {
        hours < 1 -> context.getString(R.string.widget_up_next_soon)
        hours < HOURS_PER_DAY -> context.resources.getQuantityString(R.plurals.widget_up_next_in_hours, hours.toInt(), hours.toInt())
        else -> daysUntil(context, due, now)
    }
}

private fun daysUntil(context: Context, due: Instant, now: Instant): String {
    val days = ((due - now).inWholeHours / HOURS_PER_DAY).toInt().coerceAtLeast(1)
    return context.resources.getQuantityString(R.plurals.widget_up_next_in_days, days, days)
}

private const val HOURS_PER_DAY = 24L
private const val MAX_LATER_ROWS = 10
private val MinTallHeight = 150.dp
private val MinRelativeInlineWidth = 200.dp
private val LaterRowHeight = 24.dp

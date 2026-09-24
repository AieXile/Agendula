package de.jeanlucmakiola.agendula.widget

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
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.jeanlucmakiola.agendula.MainActivity
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.data.prefs.ThemeMode
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.floret.components.pastelize
import de.jeanlucmakiola.floret.time.formatDateTimeCompact
import de.jeanlucmakiola.floret.time.formatTime
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/**
 * "Tasks" home-screen widget: one smart list (Today by default) or one real
 * list per instance, with a checkbox to complete each task in place.
 *
 * The data is collected live inside the composition rather than loaded once in
 * the `provideGlance` preamble: an `update` on a running session only
 * recomposes, so a preamble snapshot would stay stale for as long as the session
 * lives — e.g. right after a checkbox tap.
 */
class TaskWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val stored = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[WIDGET_FILTER_KEY]
        val initialFilter = parseWidgetFilter(stored)
        val initial = runCatching { context.taskWidgetData(initialFilter).first() }
            .getOrDefault(TaskWidgetData.Unavailable)
        provideContent {
            val filter = parseWidgetFilter(currentState(WIDGET_FILTER_KEY))
            val data by remember(filter) { context.taskWidgetData(filter) }
                .collectAsState(initial = if (filter == initialFilter) initial else null)
            val ready = data as? TaskWidgetData.Ready ?: initial as? TaskWidgetData.Ready
            AgendulaGlanceTheme(
                dynamicColor = ready?.dynamicColor ?: true,
                themeMode = ready?.themeMode ?: ThemeMode.SYSTEM,
            ) {
                TaskWidgetBody(filter, data)
            }
        }
    }
}

/** Completes the tapped task — just the one occurrence for a recurring series. */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[TaskIdKey] ?: return
        val occurrence = parameters[OccurrenceKey]?.takeIf { it != NO_OCCURRENCE }
            ?.let { Instant.fromEpochMilliseconds(it) }
        runCatching {
            context.widgetEntryPoint().tasksRepository().setCompleted(taskId, occurrence, true)
        }
        TaskWidget().update(context.applicationContext, glanceId)
    }

    companion object {
        val TaskIdKey = ActionParameters.Key<Long>("taskId")
        val OccurrenceKey = ActionParameters.Key<Long>("occurrenceStart")
        const val NO_OCCURRENCE = Long.MIN_VALUE
    }
}

@Composable
private fun TaskWidgetBody(filter: TaskFilter, data: TaskWidgetData?) {
    val context = LocalContext.current
    val title = when (filter) {
        is TaskFilter.Smart -> context.getString(smartListLabel(filter.list))
        is TaskFilter.OfList -> (data as? TaskWidgetData.Ready)?.listName
            ?: context.getString(R.string.widget_tasks_label)
    }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Header(title, filter)
        Spacer(GlanceModifier.height(4.dp))
        when (data) {
            null -> Unit
            TaskWidgetData.Unavailable -> Message(context.getString(R.string.widget_unavailable))
            is TaskWidgetData.Ready ->
                if (data.rows.isEmpty()) {
                    Message(context.getString(R.string.widget_empty))
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(data.rows.size) { index -> TaskRow(data.rows[index], data.use24Hour) }
                    }
                }
        }
    }
}

@Composable
private fun Header(title: String, filter: TaskFilter) {
    val context = LocalContext.current
    val open = when (filter) {
        is TaskFilter.Smart -> MainActivity.smartListIntent(context, filter.list)
        is TaskFilter.OfList -> MainActivity.listIntent(context, filter.listId)
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(open)),
        )
        Box(
            modifier = GlanceModifier
                .size(40.dp)
                .cornerRadius(20.dp)
                .background(GlanceTheme.colors.primaryContainer)
                .clickable(actionStartActivity(MainActivity.newTaskIntent(context))),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_add),
                contentDescription = context.getString(R.string.new_task),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                modifier = GlanceModifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun TaskRow(row: WidgetTaskRow, use24Hour: Boolean) {
    val context = LocalContext.current
    val title = row.title.ifBlank { context.getString(R.string.task_untitled) }
    val open = actionStartActivity(
        MainActivity.taskIntent(context, row.taskId, row.occurrenceStart?.toEpochMilliseconds()),
    )
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .width(4.dp)
                .height(32.dp)
                .cornerRadius(2.dp)
                .background(ColorProvider(day = pastelize(row.color, false), night = pastelize(row.color, true))),
        ) {}
        Box(
            modifier = GlanceModifier
                .size(40.dp)
                .clickable(
                    actionRunCallback<CompleteTaskAction>(
                        actionParametersOf(
                            CompleteTaskAction.TaskIdKey to row.taskId,
                            CompleteTaskAction.OccurrenceKey to
                                (row.occurrenceStart?.toEpochMilliseconds() ?: CompleteTaskAction.NO_OCCURRENCE),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_checkbox),
                contentDescription = context.getString(R.string.widget_complete_task, title),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.size(22.dp),
            )
        }
        Column(
            modifier = GlanceModifier.defaultWeight().padding(end = 8.dp, top = 4.dp, bottom = 4.dp)
                .clickable(open),
        ) {
            Text(
                text = title,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
            )
            dueText(context, row, use24Hour)?.let { due ->
                Text(
                    text = due,
                    maxLines = 1,
                    style = TextStyle(
                        color = if (row.isOverdue) GlanceTheme.colors.error
                        else GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
        )
    }
}

/** "Today · 14:00", "Tomorrow", or the compact date; `null` without a due date. */
private fun dueText(context: Context, row: WidgetTaskRow, use24Hour: Boolean): String? {
    val due = row.due ?: return null
    val relative = when (row.dueKind) {
        DueKind.TODAY -> context.getString(R.string.home_due_today)
        DueKind.TOMORROW -> context.getString(R.string.home_due_tomorrow)
        else -> null
    } ?: return due.formatDateTimeCompact(row.isAllDay, use24Hour)
    return if (row.isAllDay) relative else "$relative · ${due.formatTime(use24Hour)}"
}

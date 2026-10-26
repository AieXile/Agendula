package de.jeanlucmakiola.agendula.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
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
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GlanceColorProvider
import de.jeanlucmakiola.agendula.MainActivity
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.domain.SmartList
import de.jeanlucmakiola.agendula.domain.TaskFilter
import de.jeanlucmakiola.agendula.ui.theme.AgendulaDarkFallback
import de.jeanlucmakiola.agendula.ui.theme.AgendulaLightFallback
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.pastelize
import de.jeanlucmakiola.floret.glance.FloretGlanceTheme
import de.jeanlucmakiola.floret.glance.WidgetBackground
import de.jeanlucmakiola.floret.glance.WidgetCheckButton
import de.jeanlucmakiola.floret.glance.WidgetGroupedRow
import de.jeanlucmakiola.floret.glance.WidgetHeader
import de.jeanlucmakiola.floret.glance.WidgetHeaderAction
import de.jeanlucmakiola.floret.glance.WidgetDoneState
import de.jeanlucmakiola.floret.glance.WidgetMessage
import de.jeanlucmakiola.floret.glance.WidgetSurface
import de.jeanlucmakiola.floret.glance.WidgetThemeMode
import de.jeanlucmakiola.floret.time.formatDateTimeCompact
import de.jeanlucmakiola.floret.time.formatTime
import de.jeanlucmakiola.floret.glance.R as GlanceR
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/**
 * "Tasks" home-screen widget: one smart list (Today by default) or one or more
 * real lists per instance, with a Calendar-agenda look — a plain section label
 * per group (by list, or by due bucket: Overdue / Today / Tomorrow / Later /
 * No date) above a run of standalone, fully-rounded task pills, each with a
 * trailing check to complete it in place.
 *
 * The data is collected live inside the composition rather than loaded once in
 * the `provideGlance` preamble: an `update` on a running session only
 * recomposes, so a preamble snapshot would stay stale for as long as the session
 * lives — e.g. right after a checkbox tap.
 */
class TaskWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(setOf(CompactSize, RegularSize))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val initialFilter = parseWidgetFilter(state[WIDGET_FILTER_KEY])
        val initialGroupByList = state[WIDGET_GROUP_BY_LIST_KEY] ?: false
        val initial = runCatching { context.taskWidgetData(initialFilter, initialGroupByList).first() }
            .getOrDefault(TaskWidgetData.Unavailable)
        provideContent {
            val filter = parseWidgetFilter(currentState(WIDGET_FILTER_KEY))
            val groupByList = currentState(WIDGET_GROUP_BY_LIST_KEY) ?: false
            val background = parseWidgetBackground(currentState(WIDGET_BACKGROUND_KEY))
            val themeOverride = parseWidgetThemeOverride(currentState(WIDGET_THEME_KEY))
            val rowColor = parseWidgetRowColor(currentState(WIDGET_ROW_COLOR_KEY))
            val data by remember(filter, groupByList) { context.taskWidgetData(filter, groupByList) }
                .collectAsState(initial = if (filter == initialFilter && groupByList == initialGroupByList) initial else null)
            val ready = data as? TaskWidgetData.Ready ?: initial as? TaskWidgetData.Ready
            FloretGlanceTheme(
                lightScheme = AgendulaLightFallback,
                darkScheme = AgendulaDarkFallback,
                dynamicColor = ready?.dynamicColor ?: true,
                mode = ready?.effectiveThemeMode(themeOverride) ?: WidgetThemeMode.SYSTEM,
            ) {
                TaskWidgetBody(filter, data, background, rowColor)
            }
        }
    }

    private companion object {
        val CompactSize = DpSize(110.dp, 110.dp)
        val RegularSize = DpSize(250.dp, 110.dp)
    }
}

/** Completes the tapped task — just the one occurrence for a recurring series. */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[TaskIdKey] ?: return
        val occurrenceMillis = parameters[OccurrenceKey]?.takeIf { it != NO_OCCURRENCE }
        val key = WidgetCompletions.key(taskId, occurrenceMillis)
        if (!WidgetCompletions.start(key)) return
        val app = context.applicationContext
        // This callback is shared by every widget's rows, and glanceId only
        // tells us the tapped widget's instance, not which provider class it
        // belongs to — updating the wrong one by id would push this class's
        // RemoteViews into another widget's AppWidget slot. updateAll is
        // class-scoped, so redrawing every widget is the immediate, correct
        // option; it also covers every other open instance showing this task.
        // First the ticked frame, then the real completion a beat later.
        updateAllWidgets(app)
        delay(TICK_FRAME_MILLIS)
        runCatching {
            context.widgetEntryPoint().tasksRepository()
                .setCompleted(taskId, occurrenceMillis?.let { Instant.fromEpochMilliseconds(it) }, true)
        }
        updateAllWidgets(app)
        // Hold the ticked look until the store's change has redrawn the row away.
        delay(TICK_RELEASE_MILLIS)
        WidgetCompletions.finish(key)
        updateAllWidgets(app)
    }

    companion object {
        val TaskIdKey = ActionParameters.Key<Long>("taskId")
        val OccurrenceKey = ActionParameters.Key<Long>("occurrenceStart")
        const val NO_OCCURRENCE = Long.MIN_VALUE
        private const val TICK_FRAME_MILLIS = 600L
        private const val TICK_RELEASE_MILLIS = 1500L
    }
}

@Composable
private fun TaskWidgetBody(
    filter: TaskFilter,
    data: TaskWidgetData?,
    background: WidgetBackground,
    rowColor: WidgetRowColor,
) {
    val context = LocalContext.current
    val ready = data as? TaskWidgetData.Ready
    val title = when (filter) {
        is TaskFilter.Smart -> context.getString(smartListLabel(filter.list))
        is TaskFilter.Tagged -> filter.tag
        is TaskFilter.Saved -> if (ready == null) {
            context.getString(R.string.widget_tasks_label)
        } else {
            ready.filterName ?: context.getString(R.string.filter_missing)
        }
        is TaskFilter.OfList, is TaskFilter.OfLists -> ready?.singleListName
            ?: context.getString(R.string.widget_tasks_label)
    }
    WidgetSurface(background = background) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            TaskWidgetHeader(title, filter, ready?.singleListId)
            when (data) {
                null -> Unit
                TaskWidgetData.Unavailable -> {
                    Spacer(GlanceModifier.height(R.dimen.widget_tasks_header_gap_tight))
                    WidgetMessage(context.getString(R.string.widget_unavailable))
                }
                is TaskWidgetData.Ready ->
                    if (data.rows.isEmpty()) {
                        Spacer(GlanceModifier.height(R.dimen.widget_tasks_header_gap_tight))
                        WidgetDoneState(context.getString(R.string.widget_empty), context.getString(R.string.widget_empty_hint))
                    } else {
                        val allSections = widgetSections(
                            data.rows,
                            data.groupByList,
                            data.listNames,
                        ) { kind -> context.getString(dueBucketLabel(kind)) }
                        // A lone group whose bucket repeats the widget's own smart list
                        // (the "Today" smart list showing one "Today" bucket) earns no
                        // place of its own. A lone group for any other bucket (a
                        // real-list widget whose only bucket is "Tomorrow") still has to
                        // say so — it's the one thing telling the rows apart from today.
                        // Compared by due kind, not by rendered label text, since the two
                        // labels come from separately translated string resources and
                        // aren't guaranteed to read identically in every locale.
                        val onlyHeader = allSections.singleOrNull { it is WidgetSection.Header } as? WidgetSection.Header
                        val redundantDueKind = when ((filter as? TaskFilter.Smart)?.list) {
                            SmartList.TODAY -> DueKind.TODAY
                            SmartList.OVERDUE -> DueKind.PAST
                            else -> null
                        }
                        val redundantHeader = onlyHeader != null && redundantDueKind != null &&
                            onlyHeader.key == "due:${redundantDueKind.name}"
                        val sections = if (redundantHeader) allSections.filterNot { it is WidgetSection.Header } else allSections
                        val leadingGap = if (sections.firstOrNull() is WidgetSection.TaskItem) {
                            R.dimen.widget_tasks_header_gap
                        } else {
                            R.dimen.widget_tasks_header_gap_tight
                        }
                        Spacer(GlanceModifier.height(leadingGap))
                        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                            items(sections.size) { index ->
                                when (val section = sections[index]) {
                                    is WidgetSection.Header -> SectionHeader(section.name)
                                    is WidgetSection.TaskItem -> {
                                        val container = if (rowColor == WidgetRowColor.BY_LIST) {
                                            ColorProvider(
                                                day = pastelize(section.row.color, false),
                                                night = pastelize(section.row.color, true),
                                            )
                                        } else {
                                            GlanceTheme.colors.surfaceVariant
                                        }
                                        // A LazyColumn item's host view isn't a linear layout on
                                        // its own, so the row and its gap need an explicit Column
                                        // to stack rather than overlap.
                                        Column {
                                            AgendaTaskRow(
                                                row = section.row,
                                                use24Hour = data.use24Hour,
                                                color = container,
                                                colored = rowColor == WidgetRowColor.BY_LIST,
                                                // The due-bucket header above already names the day
                                                // (Today / Tomorrow) whenever one is shown — and when
                                                // it isn't, that's only because it would have repeated
                                                // the title, which names the same day just as well.
                                                suppressDayLabel = !data.groupByList,
                                            )
                                            Spacer(GlanceModifier.height(8.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }
}

private fun dueBucketLabel(kind: DueKind?): Int = when (kind) {
    DueKind.PAST -> R.string.widget_section_overdue
    DueKind.TODAY -> R.string.home_due_today
    DueKind.TOMORROW -> R.string.home_due_tomorrow
    DueKind.LATER -> R.string.widget_section_later
    null -> R.string.widget_section_no_date
}

@Composable
private fun SectionHeader(name: String) {
    Text(
        text = name,
        maxLines = 1,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun TaskWidgetHeader(title: String, filter: TaskFilter, singleListId: Long?) {
    val context = LocalContext.current
    val open = when (filter) {
        is TaskFilter.Smart -> MainActivity.smartListIntent(context, filter.list)
        is TaskFilter.OfList -> MainActivity.listIntent(context, filter.listId)
        // Several lists have no single list screen to open; a single selection
        // still opens straight to it, otherwise this just raises the app.
        is TaskFilter.OfLists -> filter.listIds.singleOrNull()?.let { MainActivity.listIntent(context, it) }
            ?: Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        is TaskFilter.Tagged -> Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        is TaskFilter.Saved -> MainActivity.filterIntent(context, filter.id)
    }
    WidgetHeader(
        title = title,
        titleAction = actionStartActivity(open),
    ) {
        WidgetHeaderAction(
            icon = ImageProvider(GlanceR.drawable.floret_ic_widget_add),
            contentDescription = context.getString(R.string.new_task),
            action = actionStartActivity(MainActivity.newTaskIntent(context, singleListId)),
        )
    }
}

/**
 * A Calendar-agenda-style task chip: its own fully-rounded pill (list colour
 * or a neutral tone, per [WidgetRowColor]), a leading check to complete it,
 * title and due to its right. [colored] pills use fixed light text/icon —
 * [pastelize][de.jeanlucmakiola.floret.components.pastelize] keeps them
 * mid-bright rather than pale, so the theme's own `onSurface` (tuned for the
 * neutral tonal fill) reads too dark against them.
 */
@Composable
internal fun AgendaTaskRow(
    row: WidgetTaskRow,
    use24Hour: Boolean,
    color: GlanceColorProvider,
    colored: Boolean,
    suppressDayLabel: Boolean,
) {
    val context = LocalContext.current
    val title = row.title.ifBlank { context.getString(R.string.task_untitled) }
    val open = actionStartActivity(
        MainActivity.taskIntent(context, row.taskId, row.occurrenceStart?.toEpochMilliseconds()),
    )
    val onColor = if (colored) GlanceColorProvider(Color.White) else GlanceTheme.colors.onSurface
    val onColorVariant = if (colored) GlanceColorProvider(Color.White.copy(alpha = 0.8f)) else GlanceTheme.colors.onSurfaceVariant
    val onColorError = if (colored) GlanceColorProvider(Color.White) else GlanceTheme.colors.error
    val completing = WidgetCompletions.isCompleting(row)
    WidgetGroupedRow(position = Position.Alone, color = color) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetCheckButton(
                contentDescription = context.getString(R.string.widget_complete_task, title),
                action = actionRunCallback<CompleteTaskAction>(
                    actionParametersOf(
                        CompleteTaskAction.TaskIdKey to row.taskId,
                        CompleteTaskAction.OccurrenceKey to
                            (row.occurrenceStart?.toEpochMilliseconds() ?: CompleteTaskAction.NO_OCCURRENCE),
                    ),
                ),
                tint = onColorVariant,
                checked = completing,
            )
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight().clickable(open)) {
                Text(
                    text = title,
                    maxLines = 1,
                    style = TextStyle(
                        color = if (completing) onColorVariant else onColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (completing) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                )
                dueText(context, row, use24Hour, suppressDayLabel)?.let { due ->
                    Text(
                        text = due,
                        maxLines = 1,
                        style = TextStyle(
                            color = if (row.isOverdue) onColorError else onColorVariant,
                            fontSize = 12.sp,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The chip's own due line — bare "14:00" when [suppressDayLabel] (the due-bucket
 * header above already says "Today"/"Tomorrow", Calendar-agenda style: the day
 * is the group, the chip only needs the time), otherwise "Today · 14:00" /
 * "Tomorrow", or the compact date for anything else. `null` without a due date,
 * or for an all-day task once its day is already said by the header.
 */
internal fun dueText(context: Context, row: WidgetTaskRow, use24Hour: Boolean, suppressDayLabel: Boolean): String? {
    val due = row.due ?: return null
    val relative = when (row.dueKind) {
        DueKind.TODAY -> context.getString(R.string.home_due_today)
        DueKind.TOMORROW -> context.getString(R.string.home_due_tomorrow)
        else -> null
    } ?: return due.formatDateTimeCompact(row.isAllDay, use24Hour)
    if (row.isAllDay) return if (suppressDayLabel) null else relative
    val time = due.formatTime(use24Hour)
    return if (suppressDayLabel) time else "$relative · $time"
}

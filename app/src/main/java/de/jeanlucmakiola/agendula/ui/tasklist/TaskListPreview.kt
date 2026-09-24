package de.jeanlucmakiola.agendula.ui.tasklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.domain.TaskStatus
import de.jeanlucmakiola.agendula.ui.common.DefaultListColor
import de.jeanlucmakiola.floret.components.ScaledViewPreview
import de.jeanlucmakiola.floret.components.positionOf

/**
 * A task list as it will look, for a chooser that offers a choice about it —
 * today only the bottom quick-add bar against the floating "New task" button.
 *
 * Drawn from the screen's own [TaskRowContent] and [InlineAdd] over sample
 * tasks, so what the preview shows cannot drift from what the app does. The
 * chrome around them (a title, the FAB) is a still of the real scaffold rather
 * than the scaffold itself: [TaskListScreen] needs a ViewModel, a list id and a
 * store behind it, none of which exist during onboarding.
 */
@Composable
internal fun TaskListPreview(bottomAddBar: Boolean, height: Dp) {
    ScaledViewPreview(height = height) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.tasks_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 16.dp),
                )
                SampleTasks.forEachIndexed { index, task ->
                    TaskRowContent(
                        task = task,
                        position = positionOf(index, SampleTasks.size),
                        showListName = false,
                        subtaskDone = task.subtaskDone,
                        subtaskTotal = task.subtaskTotal,
                        expanded = false,
                        onToggleExpand = null,
                        onToggle = {},
                        onClick = {},
                    )
                    if (index != SampleTasks.lastIndex) Spacer(Modifier.height(2.dp))
                }
            }

            if (bottomAddBar) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    InlineAdd(onAdd = {}, elevated = true)
                }
            } else {
                ExtendedFloatingActionButton(
                    onClick = {},
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_task)) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }
        }
    }
}

/** Enough rows to fill the viewport, and varied enough to show a meta line. */
private val SampleTasks: List<Task> = listOf(
    sampleTask(1, "Water the balcony plants", priority = Priority.MEDIUM),
    sampleTask(2, "Book the dentist", subtaskTotal = 3, subtaskDone = 1),
    sampleTask(3, "Reply to the landlord"),
    sampleTask(4, "Pick up the parcel", status = TaskStatus.COMPLETED),
)

private fun sampleTask(
    id: Long,
    title: String,
    priority: Priority = Priority.NONE,
    status: TaskStatus = TaskStatus.NEEDS_ACTION,
    subtaskTotal: Int = 0,
    subtaskDone: Int = 0,
) = Task(
    taskId = id,
    listId = 1,
    title = title,
    description = null,
    location = null,
    url = null,
    priority = priority,
    status = status,
    percentComplete = null,
    start = null,
    due = null,
    isAllDay = false,
    timeZone = null,
    completedAt = null,
    listColor = DefaultListColor,
    taskColor = null,
    listName = null,
    accountName = null,
    parentId = null,
    isRecurring = false,
    distanceFromCurrent = null,
    created = null,
    lastModified = null,
    subtaskTotal = subtaskTotal,
    subtaskDone = subtaskDone,
)

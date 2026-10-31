package com.aiexile.agendula.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiexile.agendula.R
import com.aiexile.agendula.domain.Task
import com.aiexile.agendula.ui.tasklist.TaskRowContent
import de.jeanlucmakiola.floret.components.positionOf

/** How tall the feed grows before its rows start scrolling inside it. */
private val FeedMaxHeight = 360.dp

/**
 * The home's task feed: the open tasks across every visible list, in the
 * repository's default order, inside a capped region that scrolls on its own.
 *
 * The header stays put while the rows scroll under it, and is the way into the
 * full "All" list — the tiles this replaced had no other entry point left.
 * Rows are the task list's own [TaskRowContent] (checkbox, meta line, tap to open)
 * so the home cannot drift from what a list looks like; swiping is deliberately
 * absent here, where a stray gesture should not delete anything.
 */
@Composable
internal fun HomeTaskFeed(
    tasks: List<Task>,
    onOpenAll: () -> Unit,
    onOpenTask: (Task) -> Unit,
    onToggle: (Task) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FeedHeader(count = tasks.size, onOpenAll = onOpenAll)
        if (tasks.isEmpty()) {
            Text(
                text = stringResource(R.string.home_tasks_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 4.dp, bottom = 16.dp),
            )
        } else {
            // heightIn(max = …) rather than a fixed height: a short feed hugs its
            // rows instead of leaving a hole, and a long one stops growing here.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = FeedMaxHeight),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                itemsIndexed(tasks, key = { _, task -> task.occurrenceKey }) { index, task ->
                    val gap = if (index == tasks.lastIndex) Modifier else Modifier.padding(bottom = 2.dp)
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(gap)) {
                        TaskRowContent(
                            task = task,
                            position = positionOf(index, tasks.size),
                            showListName = true,
                            subtaskDone = task.subtaskDone,
                            subtaskTotal = task.subtaskTotal,
                            expanded = false,
                            onToggleExpand = null,
                            onToggle = { onToggle(task) },
                            onClick = { onOpenTask(task) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * "All" + how many, with the chevron that says the row leads somewhere. Tapping
 * it opens the All smart list, where the same tasks continue past this cap.
 */
@Composable
private fun FeedHeader(count: Int, onOpenAll: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenAll)
            .padding(start = 28.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.smart_all),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = muted,
        )
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

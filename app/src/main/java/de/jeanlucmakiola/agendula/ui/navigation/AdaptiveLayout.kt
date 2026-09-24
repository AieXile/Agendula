package de.jeanlucmakiola.agendula.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.domain.Task
import de.jeanlucmakiola.agendula.ui.detail.TaskDetailScreen
import de.jeanlucmakiola.agendula.ui.detail.TaskDetailViewModel
import kotlin.time.Instant

/** Wide enough for a list and a task side by side: Material 3's expanded width class. */
internal val LIST_DETAIL_MIN_WIDTH = 840.dp

private val MAX_CONTENT_WIDTH = 720.dp

/**
 * A phone-shaped column centred on a tablet or a landscape screen: a task list
 * or a form stretched edge to edge across 1200dp reads as a spreadsheet.
 */
@Composable
internal fun ReadableWidth(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(modifier = Modifier.fillMaxSize().widthIn(max = MAX_CONTENT_WIDTH)) { content() }
    }
}

/**
 * The task list with the selected task's detail beside it, for wide windows.
 * Opening a task selects it instead of navigating; back clears the selection
 * before it leaves the list.
 */
@Composable
internal fun TaskListDetailPanes(
    list: @Composable (onOpenTask: (Task) -> Unit) -> Unit,
    onEdit: (Task) -> Unit,
) {
    var selectedId by rememberSaveable { mutableLongStateOf(Dest.NO_ID) }
    var selectedOccurrence by rememberSaveable { mutableLongStateOf(Dest.NO_ID) }
    val select: (Task) -> Unit = { task ->
        selectedId = task.taskId
        selectedOccurrence = task.occurrenceStart?.toEpochMilliseconds() ?: Dest.NO_ID
    }
    val clear = { selectedId = Dest.NO_ID }
    BackHandler(enabled = selectedId != Dest.NO_ID, onBack = clear)

    Row(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxHeight()) { list(select) }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
            modifier = Modifier.weight(1.2f).fillMaxHeight(),
        ) {
            if (selectedId == Dest.NO_ID) {
                EmptyDetailPane()
            } else {
                val vm: TaskDetailViewModel = hiltViewModel(key = "task-detail-pane")
                LaunchedEffect(selectedId, selectedOccurrence) {
                    vm.bind(
                        selectedId,
                        selectedOccurrence.takeIf { it != Dest.NO_ID }?.let(Instant::fromEpochMilliseconds),
                    )
                }
                TaskDetailScreen(
                    viewModel = vm,
                    onEdit = onEdit,
                    onDeleted = clear,
                    onBack = clear,
                    onOpenTask = select,
                    onDuplicated = { id ->
                        selectedId = id
                        selectedOccurrence = Dest.NO_ID
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyDetailPane() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.TaskAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(R.string.detail_pane_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

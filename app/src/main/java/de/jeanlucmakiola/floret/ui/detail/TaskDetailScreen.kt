package de.jeanlucmakiola.floret.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.floret.R
import de.jeanlucmakiola.floret.domain.TaskDetail

/**
 * One task's detail. **Base scaffold** — title, description and subtask titles,
 * with edit / delete in the top bar, against the already-built
 * [TaskDetailViewModel]. M3 fleshes this out with dates, priority,
 * percent-complete and subtask presentation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.task_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    val content = state as? TaskDetailUiState.Content
                    IconButton(onClick = onEdit, enabled = content != null) {
                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.edit))
                    }
                    IconButton(
                        onClick = {
                            content?.let {
                                viewModel.delete(it.detail.task)
                                onDeleted()
                            }
                        },
                        enabled = content != null,
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        },
    ) { inner ->
        when (val s = state) {
            TaskDetailUiState.Loading -> Unit
            TaskDetailUiState.NotFound ->
                CenteredMessage(stringResource(R.string.task_detail_not_found), inner)
            is TaskDetailUiState.Content -> DetailBody(s.detail, inner)
        }
    }
}

@Composable
private fun DetailBody(detail: TaskDetail, inner: androidx.compose.foundation.layout.PaddingValues) {
    val task = detail.task
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(inner)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = task.title.ifBlank { stringResource(R.string.task_untitled) },
            style = MaterialTheme.typography.headlineSmall,
        )
        task.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        detail.subtasks.forEach { sub ->
            Text(
                text = "• " + sub.title.ifBlank { stringResource(R.string.task_untitled) },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun CenteredMessage(text: String, inner: androidx.compose.foundation.layout.PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text) }
}

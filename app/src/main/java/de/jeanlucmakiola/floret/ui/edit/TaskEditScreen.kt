package de.jeanlucmakiola.floret.ui.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.floret.R

/**
 * Create / edit form. **Base scaffold** — title + description fields and save,
 * against the already-built [TaskEditViewModel]; it flips `saved` for us to pop
 * back. M3 layers on date-time pickers, priority, list selection,
 * percent-complete and per-task reminders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.new_task_title else R.string.edit_task_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::save, enabled = !state.loading) {
                        Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.save))
                    }
                },
            )
        },
    ) { inner ->
        if (state.loading) return@Scaffold

        Column(
            modifier = Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text(stringResource(R.string.field_title)) },
                singleLine = true,
                isError = state.errors.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text(stringResource(R.string.field_description)) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            if (state.saveFailed) {
                Text(
                    text = stringResource(R.string.edit_save_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

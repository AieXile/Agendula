package de.jeanlucmakiola.agendula.ui.imports

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.ui.common.DefaultListColor
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.GroupedListInset
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.OptionPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.pastelize

private val PICK_TYPES = arrayOf("text/calendar", "text/x-vcalendar", "application/zip", "*/*")

/**
 * Import tasks from an `.ics` file, or a zip of them as the export writes. The
 * file is picked through SAF (or handed in as [initialUri] from an opened
 * file), the target is an existing writable list or a new device-only one, and
 * the outcome stays on screen as a receipt.
 *
 * Only the own store can take an import; [onOpenStorage], when given, offers
 * the way there.
 */
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    predictiveBack: Boolean = false,
    visible: Boolean? = null,
    initialUri: Uri? = null,
    onOpenStorage: (() -> Unit)? = null,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showTargets by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialUri, state.ownStore) {
        if (initialUri != null && state.ownStore) viewModel.open(initialUri)
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::open) }
    val pick = {
        viewModel.reset()
        launcher.launch(PICK_TYPES)
    }

    val defaultName = stringResource(R.string.import_new_list_default_name)
    val ready = state.step as? ImportStep.Ready
    val newListOption = ImportChoice.NewList(
        ready?.parsed?.suggestedListName?.takeIf { it.isNotBlank() } ?: defaultName,
    )
    val selected = state.choice ?: newListOption

    CollapsingScaffold(
        title = stringResource(R.string.import_title),
        onBack = onBack,
        modifier = modifier,
        predictiveBack = predictiveBack,
        visible = visible,
    ) {
        Text(
            text = stringResource(R.string.import_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = GroupedListInset, vertical = 4.dp),
        )
        Spacer(Modifier.height(16.dp))

        if (!state.ownStore) {
            OwnStoreOnly(onOpenStorage)
            return@CollapsingScaffold
        }

        when (val step = state.step) {
            ImportStep.Idle -> PickButton(onClick = pick)
            ImportStep.Reading -> Progress(stringResource(R.string.import_reading))
            ImportStep.Importing -> Progress(stringResource(R.string.import_running))
            is ImportStep.Ready -> {
                ReadyContent(
                    step = step,
                    target = selected,
                    onChangeTarget = { showTargets = true },
                )
                Spacer(Modifier.height(24.dp))
                ActionTiles(
                    primaryIcon = Icons.Rounded.Download,
                    primaryLabel = stringResource(R.string.import_button),
                    onPrimary = { viewModel.import(DefaultListColor, defaultName) },
                    secondaryIcon = Icons.Rounded.FileOpen,
                    secondaryLabel = stringResource(R.string.import_pick_other),
                    onSecondary = pick,
                )
            }
            is ImportStep.Done -> {
                DoneReceipt(step)
                Spacer(Modifier.height(24.dp))
                PickButton(onClick = pick, label = stringResource(R.string.import_pick_another))
            }
            is ImportStep.Failed -> {
                FailedReceipt(step)
                Spacer(Modifier.height(24.dp))
                PickButton(onClick = pick, label = stringResource(R.string.import_pick_other))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showTargets && ready != null) {
        TargetPicker(
            options = listOf<ImportChoice>(newListOption) + state.lists.map { ImportChoice.Existing(it) },
            selected = selected,
            onSelect = viewModel::choose,
            onDismiss = { showTargets = false },
        )
    }
}

@Composable
private fun OwnStoreOnly(onOpenStorage: (() -> Unit)?) {
    GroupedRow(
        title = stringResource(R.string.import_own_store_only),
        position = Position.Alone,
        leading = { Icon(Icons.Rounded.Info, contentDescription = null) },
        trailing = onOpenStorage?.let { open ->
            { TextButton(onClick = open) { Text(stringResource(R.string.import_open_storage)) } }
        },
    )
}

@Composable
private fun ReadyContent(step: ImportStep.Ready, target: ImportChoice, onChangeTarget: () -> Unit) {
    val parsed = step.parsed
    val found = pluralStringResource(R.plurals.import_found, parsed.tasks.size, parsed.tasks.size)
    val events = if (parsed.skippedComponents > 0) {
        pluralStringResource(
            R.plurals.import_events_left_out,
            parsed.skippedComponents,
            parsed.skippedComponents,
        )
    } else {
        null
    }
    GroupedRow(
        title = parsed.fileName ?: stringResource(R.string.import_picked_file),
        summary = listOfNotNull(found, events).joinToString(" · "),
        position = Position.Top,
        leading = { Icon(Icons.Rounded.Description, contentDescription = null) },
    )
    GroupedRow(
        title = stringResource(R.string.import_target_label),
        summary = targetLabel(target),
        position = Position.Bottom,
        leading = { TargetIcon(target) },
        onClick = onChangeTarget,
    )
}

@Composable
private fun TargetPicker(
    options: List<ImportChoice>,
    selected: ImportChoice,
    onSelect: (ImportChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionPicker(
        title = stringResource(R.string.import_target_label),
        options = options,
        selected = selected,
        label = { targetLabel(it) },
        summary = { option ->
            when (option) {
                is ImportChoice.NewList -> stringResource(R.string.import_new_list_hint)
                is ImportChoice.Existing -> option.list.accountName.takeIf { !option.list.isLocal }
            }
        },
        leading = { TargetIcon(it) },
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

@Composable
private fun targetLabel(choice: ImportChoice): String = when (choice) {
    is ImportChoice.NewList -> stringResource(R.string.import_new_list, choice.name)
    is ImportChoice.Existing -> choice.list.name
}

@Composable
private fun TargetIcon(choice: ImportChoice) {
    when (choice) {
        is ImportChoice.NewList -> Icon(Icons.Rounded.Add, contentDescription = null)
        is ImportChoice.Existing -> Icon(
            Icons.Rounded.Circle,
            contentDescription = null,
            tint = pastelize(choice.list.color, isSystemInDarkTheme()),
        )
    }
}

@Composable
private fun DoneReceipt(step: ImportStep.Done) {
    val notes = listOfNotNull(
        step.skippedExisting.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.import_done_skipped_existing, it, it)
        },
        step.skippedComponents.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.import_events_left_out, it, it)
        },
    )
    Receipt(
        title = pluralStringResource(R.plurals.import_done, step.imported, step.imported, step.listName),
        summary = notes.joinToString(" · ").takeIf { it.isNotEmpty() },
        icon = Icons.Rounded.CheckCircle,
        container = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun FailedReceipt(step: ImportStep.Failed) {
    Receipt(
        title = stringResource(
            when (step.problem) {
                ImportStep.Problem.UNREADABLE -> R.string.import_failed_unreadable
                ImportStep.Problem.NO_TASKS -> R.string.import_failed_no_tasks
                ImportStep.Problem.WRITE_FAILED -> R.string.import_failed_write
            },
        ),
        summary = step.skippedComponents.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.import_events_left_out, it, it)
        },
        icon = Icons.Rounded.ErrorOutline,
        container = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
private fun Receipt(
    title: String,
    summary: String?,
    icon: ImageVector,
    container: Color,
    contentColor: Color,
) {
    GroupedRow(
        title = title,
        summary = summary,
        position = Position.Alone,
        container = container,
        contentColor = contentColor,
        leading = { Icon(icon, contentDescription = null) },
    )
}

@Composable
private fun Progress(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = GroupedListInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One large expressive tile, the export's destination buttons' single sibling. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PickButton(onClick: () -> Unit, label: String = stringResource(R.string.import_pick)) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        contentPadding = TILE_PADDING,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GroupedListInset)
            .heightIn(min = 88.dp),
    ) { TileContent(Icons.Rounded.FileOpen, label) }
}

/** Two tiles as one expressive button group, the same pairing as the export's. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionTiles(
    primaryIcon: ImageVector,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryIcon: ImageVector,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    ButtonGroup(
        overflowIndicator = {},
        modifier = Modifier.fillMaxWidth().padding(horizontal = GroupedListInset),
    ) {
        customItem(
            buttonGroupContent = { Tile(primaryIcon, primaryLabel, onPrimary, filled = true) },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = { Tile(secondaryIcon, secondaryLabel, onSecondary, filled = false) },
            menuContent = {},
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonGroupScope.Tile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    filled: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val modifier = Modifier
        .weight(1f)
        .animateWidth(interaction)
        .heightIn(min = 88.dp)
    if (filled) {
        Button(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            interactionSource = interaction,
            contentPadding = TILE_PADDING,
            modifier = modifier,
        ) { TileContent(icon, label) }
    } else {
        FilledTonalButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            interactionSource = interaction,
            contentPadding = TILE_PADDING,
            modifier = modifier,
        ) { TileContent(icon, label) }
    }
}

@Composable
private fun TileContent(icon: ImageVector, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

private val TILE_PADDING = PaddingValues(horizontal = 12.dp, vertical = 16.dp)

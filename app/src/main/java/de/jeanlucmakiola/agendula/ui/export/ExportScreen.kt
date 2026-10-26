package de.jeanlucmakiola.agendula.ui.export

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
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
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.data.export.ExportFailure
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.GroupedListInset
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.pastelize
import de.jeanlucmakiola.floret.components.positionOf

private const val ZIP_MIME = "application/zip"
private const val ZIP_NAME = "agendula-tasks.zip"
private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

/**
 * Export the task lists as iCalendar. Which lists go out is a per-list tick; the
 * destination is a folder or a single zip, both picked through SAF so the app
 * needs no storage permission.
 */
@Composable
fun ExportScreen(
    onBack: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::exportToFolder) }
    val zipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ZIP_MIME),
    ) { uri -> uri?.let(viewModel::exportToZip) }

    val canExport = !state.running && state.selectedCount > 0

    CollapsingScaffold(
        title = stringResource(R.string.settings_export),
        onBack = onBack,
        modifier = modifier,
        predictiveBack = true,
        visible = visible,
    ) {
        Text(
            text = stringResource(R.string.export_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = GroupedListInset, vertical = 4.dp),
        )
        Spacer(Modifier.height(16.dp))

        if (state.lists.isEmpty()) {
            GroupedRow(
                title = stringResource(R.string.export_no_lists),
                position = Position.Alone,
                dimmed = true,
            )
        } else {
            state.lists.forEachIndexed { index, list ->
                val selected = state.isSelected(list.id)
                GroupedRow(
                    title = list.name,
                    // The account only says something when it isn't the device itself.
                    summary = list.accountName.takeIf { !list.isLocal },
                    position = positionOf(index, state.lists.size),
                    selected = selected,
                    leading = {
                        Icon(Icons.Rounded.Circle, contentDescription = null, tint = pastelize(list.color, dark))
                    },
                    trailing = if (selected) ({ SelectedCheck() }) else null,
                    onClick = { viewModel.toggle(list.id) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        DestinationButtons(
            enabled = canExport,
            onFolder = { folderLauncher.launch(null) },
            onZip = { zipLauncher.launch(ZIP_NAME) },
        )

        Spacer(Modifier.height(16.dp))
        ExportStatus(state = state)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The two destinations as one expressive button group: the pressed tile grows
 * and squeezes its neighbour, and its corners morph square — the M3 Expressive
 * pairing, and the same press language as the app's shaped top-bar actions.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DestinationButtons(enabled: Boolean, onFolder: () -> Unit, onZip: () -> Unit) {
    ButtonGroup(
        // Two weighted tiles always fit the row, so the group never overflows.
        overflowIndicator = {},
        modifier = Modifier.fillMaxWidth().padding(horizontal = GroupedListInset),
    ) {
        customItem(
            buttonGroupContent = {
                DestinationButton(
                    icon = Icons.Rounded.Folder,
                    label = stringResource(R.string.export_to_folder),
                    onClick = onFolder,
                    enabled = enabled,
                    filled = true,
                )
            },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = {
                DestinationButton(
                    icon = Icons.Rounded.FolderZip,
                    label = stringResource(R.string.export_to_zip),
                    onClick = onZip,
                    enabled = enabled,
                    filled = false,
                )
            },
            menuContent = {},
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonGroupScope.DestinationButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    filled: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val modifier = Modifier
        .weight(1f)
        .animateWidth(interaction)
        .heightIn(min = 88.dp)
    val content: @Composable () -> Unit = {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (filled) {
        Button(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            enabled = enabled,
            interactionSource = interaction,
            contentPadding = TILE_PADDING,
            modifier = modifier,
        ) { content() }
    } else {
        FilledTonalButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            enabled = enabled,
            interactionSource = interaction,
            contentPadding = TILE_PADDING,
            modifier = modifier,
        ) { content() }
    }
}

private val TILE_PADDING = PaddingValues(horizontal = 12.dp, vertical = 16.dp)

/** The running spinner, then whatever the last export ended as — it stays put. */
@Composable
private fun ExportStatus(state: ExportUiState) {
    val outcome = state.outcome
    when {
        state.running -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = GroupedListInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.export_running),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        outcome is ExportOutcome.Success -> Receipt(
            title = pluralStringResource(R.plurals.export_done, outcome.fileCount, outcome.fileCount),
            destination = outcome.destination,
            container = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        outcome is ExportOutcome.Failure -> Receipt(
            title = stringResource(failureMessage(outcome.reason)),
            destination = outcome.destination,
            container = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            icon = Icons.Rounded.ErrorOutline,
        )
    }
}

/**
 * What the last export ended as, over *where* it was aimed: the folder or zip
 * the user picked, named, with an Open button when something on the device can
 * answer for it.
 */
@Composable
private fun Receipt(
    title: String,
    destination: ExportDestination,
    container: Color,
    contentColor: Color,
    icon: ImageVector? = null,
) {
    val context = LocalContext.current
    val name = remember(destination) { destinationName(context, destination) }
    val open = remember(destination) { openIntent(destination)?.takeIf { context.canHandle(it) } }

    GroupedRow(
        title = title,
        summary = name,
        position = Position.Alone,
        container = container,
        contentColor = contentColor,
        leading = {
            Icon(
                imageVector = icon
                    ?: if (destination.isFolder) Icons.Rounded.Folder else Icons.Rounded.FolderZip,
                contentDescription = null,
            )
        },
        trailing = if (open == null) {
            null
        } else {
            {
                TextButton(
                    onClick = { runCatching { context.startActivity(open) } },
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                ) { Text(stringResource(R.string.export_open)) }
            }
        },
    )
}

/**
 * The destination as the user would name it. The device's own provider spells the
 * path into the tree's document id (`primary:Documents/Tasks`); every other
 * provider's id is opaque, so those fall back to the display name.
 */
private fun destinationName(context: Context, destination: ExportDestination): String? = runCatching {
    if (!destination.isFolder) return@runCatching DocumentFile.fromSingleUri(context, destination.uri)?.name
    if (destination.uri.authority == EXTERNAL_STORAGE) {
        DocumentsContract.getTreeDocumentId(destination.uri).substringAfter(':', "").trim('/')
            .takeIf { it.isNotBlank() }
            ?: DocumentFile.fromTreeUri(context, destination.uri)?.name
    } else {
        DocumentFile.fromTreeUri(context, destination.uri)?.name
    }
}.getOrNull()?.takeIf { it.isNotBlank() }

/** Null when the tree `Uri` will not yield a document to point at. */
private fun openIntent(destination: ExportDestination): Intent? = runCatching {
    val uri = if (destination.isFolder) {
        DocumentsContract.buildDocumentUriUsingTree(
            destination.uri,
            DocumentsContract.getTreeDocumentId(destination.uri),
        )
    } else {
        destination.uri
    }
    val mime = if (destination.isFolder) DocumentsContract.Document.MIME_TYPE_DIR else ZIP_MIME
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}.getOrNull()

private fun Context.canHandle(intent: Intent): Boolean =
    packageManager.queryIntentActivities(intent, 0).isNotEmpty()

private fun failureMessage(reason: ExportFailure): Int = when (reason) {
    ExportFailure.FOLDER_UNAVAILABLE -> R.string.export_failed_folder
    ExportFailure.FOLDER_NOT_WRITABLE -> R.string.export_failed_read_only
    ExportFailure.CANNOT_CREATE_FILE -> R.string.export_failed_create
    ExportFailure.LOST_ACCESS -> R.string.export_failed_access
    ExportFailure.WRITE_FAILED -> R.string.export_failed
}

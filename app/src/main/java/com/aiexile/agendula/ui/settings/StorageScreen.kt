package com.aiexile.agendula.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.MoveDown
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiexile.agendula.R
import com.aiexile.agendula.data.tasks.StorageMode
import com.aiexile.agendula.data.tasks.TaskProvider
import com.aiexile.agendula.data.tasks.transfer.TransferCounts
import com.aiexile.agendula.ui.common.OnResume
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.SelectedCheck

/**
 * Where the tasks live: the store picker the resolver's `autoMode()` has always
 * assumed, plus the way out of a store that lives in our own private storage.
 */
@Composable
internal fun StorageScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    visible: Boolean,
) {
    val context = LocalContext.current
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val retryingLegacyImport by viewModel.legacyImportRetrying.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    // The store the user picked and has not yet confirmed. Switching stores moves
    // nothing, so it is worth one dialog rather than an empty app and a guess.
    var pendingMode by remember { mutableStateOf<StorageMode?>(null) }

    // The mode is committed only once the grant is in — switching first drops the
    // user on the app-wide permission gate.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        viewModel.refreshStorage()
        val granted = grants.isNotEmpty() && grants.values.all { it }
        denied = !granted
        if (granted) viewModel.setStorageMode(StorageMode.EXTERNAL)
    }

    // A provider can be installed, or its permission revoked, while we're away.
    // Deliberately does not clear [denied]: this fires on returning from the
    // permission dialog too, and would wipe the refusal before it is read.
    OnResume { viewModel.refreshStorage() }

    CollapsingScaffold(
        title = stringResource(R.string.settings_section_storage),
        onBack = onBack,
        predictiveBack = true,
        visible = visible,
    ) {
        val canCopy = storage?.canCopyFromExternal == true
        GroupedRow(
            title = stringResource(R.string.settings_task_store),
            summary = storage?.let { storeLabel(it) },
            position = if (canCopy) Position.Top else Position.Alone,
            // Nothing to pick until the stored mode has landed; opening the picker
            // on the seeded state would offer the wrong store as the current one.
            onClick = storage?.let {
                {
                    denied = false
                    showPicker = true
                }
            },
        )
        if (canCopy) {
            GroupedRow(
                title = stringResource(
                    R.string.settings_copy_from_external,
                    storage?.let { externalTitle(it.external, it.externalLabel) }.orEmpty(),
                ),
                summary = stringResource(R.string.settings_copy_from_external_hint),
                position = Position.Bottom,
                leading = { Icon(Icons.Rounded.MoveDown, contentDescription = null) },
                // Inert while a copy is in flight; the receipt below says so.
                onClick = if (transfer.running) null else viewModel::startCopyFromExternal,
            )
        }

        TransferStatus(transfer)

        // Only ever shown to someone whose upgrade import failed — their tasks are
        // still in the archived file, and this is the one way back to them.
        if (storage?.legacyImportFailed == true) {
            Spacer(Modifier.height(16.dp))
            GroupedRow(
                title = stringResource(R.string.settings_legacy_import_failed),
                summary = stringResource(
                    if (retryingLegacyImport) {
                        R.string.settings_legacy_import_retrying
                    } else {
                        R.string.settings_legacy_import_failed_hint
                    },
                ),
                position = Position.Alone,
                onClick = if (retryingLegacyImport) null else viewModel::retryLegacyImport,
            )
        }

        if (denied) {
            Spacer(Modifier.height(16.dp))
            GroupedRow(
                title = stringResource(R.string.settings_store_permission_denied),
                summary = stringResource(R.string.settings_store_permission_denied_hint),
                position = Position.Alone,
                onClick = { context.openAppSettings() },
            )
        }
    }

    storage?.let { state ->
        if (showPicker) {
            StorePicker(
                storage = state,
                // Picking the store already in use is not a switch — no dialog.
                onSelect = { mode -> if (mode != state.mode) pendingMode = mode },
                onDismiss = { showPicker = false },
            )
        }

        pendingMode?.let { mode ->
            SwitchStoreDialog(
                target = mode,
                otherStore = when (mode) {
                    StorageMode.OWN -> externalTitle(state.external, state.externalLabel)
                    StorageMode.EXTERNAL -> stringResource(R.string.settings_store_own)
                },
                onConfirm = {
                    pendingMode = null
                    val external = state.external
                    if (mode == StorageMode.EXTERNAL && external != null) {
                        // Asked even when the grant looks held: an already-granted
                        // request returns at once, a stale belief would strand them.
                        permissionLauncher.launch(
                            arrayOf(external.readPermission, external.writePermission),
                        )
                    } else {
                        viewModel.setStorageMode(mode)
                    }
                },
                onDismiss = { pendingMode = null },
            )
        }

        if (transfer.confirming) {
            CopyFromExternalDialog(
                source = externalTitle(state.external, state.externalLabel),
                counts = transfer.preview,
                onConfirm = viewModel::confirmCopyFromExternal,
                onDismiss = viewModel::dismissCopyFromExternal,
            )
        }
    }
}

/**
 * The switch itself, behind a confirm. Neither store hands its rows to the other
 * when the mode changes — the tasks stay where they were written — so the app
 * looks emptied to anyone who expected a move. Saying so once is cheaper than
 * the support thread.
 */
@Composable
private fun SwitchStoreDialog(
    target: StorageMode,
    otherStore: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_store_switch_title)) },
        text = {
            Text(
                stringResource(
                    when (target) {
                        StorageMode.OWN -> R.string.settings_store_switch_to_own
                        StorageMode.EXTERNAL -> R.string.settings_store_switch_to_external
                    },
                    otherStore,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_store_switch_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/**
 * The copy, behind a confirm that names real numbers — it is irreversible in the
 * only sense that matters: nothing merges the result back, so running it twice
 * would leave two of everything. Confirm stays disabled until the count lands.
 */
@Composable
private fun CopyFromExternalDialog(
    source: String,
    counts: TransferCounts?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.MoveDown, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_copy_confirm_title)) },
        text = {
            Text(
                text = when {
                    counts == null -> stringResource(R.string.settings_copy_counting)
                    counts.tasks == 0 -> stringResource(R.string.settings_copy_confirm_empty, source)
                    else -> pluralStringResource(
                        R.plurals.settings_copy_confirm_message,
                        counts.tasks,
                        counts.tasks,
                        source,
                    )
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = counts != null && counts.tasks > 0) {
                Text(stringResource(R.string.settings_copy_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/** The running spinner, then whatever the last copy ended as — it stays put. */
@Composable
private fun TransferStatus(state: TransferUiState) {
    val outcome = state.outcome
    when {
        state.running -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.settings_copy_running),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        outcome is TransferOutcome.Copied -> StatusText(
            text = pluralStringResource(
                R.plurals.settings_copy_done,
                outcome.counts.tasks,
                outcome.counts.tasks,
                outcome.counts.lists,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        outcome is TransferOutcome.NothingToCopy -> StatusText(
            text = stringResource(R.string.settings_copy_nothing),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        outcome is TransferOutcome.Failed -> StatusText(
            text = stringResource(R.string.settings_copy_failed),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * The two stores, as rows. External is offered only when a provider is actually
 * installed — dimmed and inert otherwise, because a mode with nothing behind it
 * empties the app.
 */
@Composable
private fun StorePicker(
    storage: StorageUiState,
    onSelect: (StorageMode) -> Unit,
    onDismiss: () -> Unit,
) {
    FullScreenPicker(title = stringResource(R.string.settings_task_store), onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.settings_task_store_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        val select: (StorageMode) -> Unit = { chosen ->
            onSelect(chosen)
            onDismiss()
        }
        val external = storage.external
        GroupedRow(
            title = stringResource(R.string.settings_store_own),
            summary = stringResource(R.string.settings_store_own_hint),
            position = Position.Top,
            selected = storage.mode == StorageMode.OWN,
            leading = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null) },
            trailing = if (storage.mode == StorageMode.OWN) {
                { SelectedCheck() }
            } else {
                null
            },
            onClick = { select(StorageMode.OWN) },
        )
        GroupedRow(
            title = externalTitle(external, storage.externalLabel),
            summary = stringResource(
                if (external == null) {
                    R.string.settings_store_external_missing
                } else {
                    R.string.settings_store_external_hint
                },
            ),
            position = Position.Bottom,
            selected = storage.mode == StorageMode.EXTERNAL,
            dimmed = external == null,
            leading = { Icon(Icons.Rounded.Apps, contentDescription = null) },
            trailing = if (storage.mode == StorageMode.EXTERNAL) {
                { SelectedCheck() }
            } else {
                null
            },
            onClick = if (external != null) ({ select(StorageMode.EXTERNAL) }) else null,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** The active store, named the way the picker names it. */
@Composable
private fun storeLabel(storage: StorageUiState): String = when (storage.mode) {
    StorageMode.OWN -> stringResource(R.string.settings_store_own)
    StorageMode.EXTERNAL -> externalTitle(storage.external, storage.externalLabel)
}

/** The provider's own app name, its authority, or the generic wording. */
@Composable
private fun externalTitle(provider: TaskProvider?, label: String?): String =
    label ?: provider?.authority ?: stringResource(R.string.settings_store_external)

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
        )
    }
}

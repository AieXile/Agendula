package com.aiexile.agendula.ui.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiexile.agendula.R
import com.aiexile.agendula.data.sync.DiscardedEdit
import com.aiexile.agendula.data.sync.SyncNotice
import com.aiexile.agendula.data.tasks.room.AccountEntity
import com.aiexile.agendula.ui.accounts.add.message
import com.aiexile.agendula.ui.common.OnResume
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.FullScreenPicker
import de.jeanlucmakiola.floret.components.GroupedListInset
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.SelectedCheck
import de.jeanlucmakiola.floret.components.positionOf

/**
 * One account: who it is, how its last sync went, and what can be done to it.
 *
 * Tapping a row in the list opens this rather than a destructive prompt — a list
 * row leads somewhere, it does not fire an irreversible action — so removing an
 * account is a button on the account's own screen.
 */
@Composable
internal fun AccountDetailScreen(
    accountId: Long,
    onBack: () -> Unit,
    onRemoved: () -> Unit,
    onSignInAgain: (AccountEntity) -> Unit,
    onOpenStorage: () -> Unit,
    viewModel: AccountsViewModel,
    visible: Boolean,
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val usable by viewModel.accountsUsable.collectAsStateWithLifecycle()
    val push by viewModel.push.collectAsStateWithLifecycle()
    OnResume(viewModel::refreshPush)
    val row = accounts?.firstOrNull { it.account.id == accountId }

    // The row is gone the instant it is removed, while this screen is still
    // sliding away. Keeping the last one it had stops that exit animating an
    // empty screen.
    var lastKnown by remember(accountId) { mutableStateOf(row) }
    LaunchedEffect(row) { if (row != null) lastKnown = row }
    val shown = row ?: lastKnown ?: return

    val account = shown.account
    val identity = account.identity()
    // The confirmation sheet. The removal it starts outlives this screen, and is
    // shown on the accounts list rather than here.
    var confirming by remember { mutableStateOf(false) }

    CollapsingScaffold(title = identity.title, onBack = onBack, predictiveBack = true, visible = visible) {
        AccountHero(identity)
        Spacer(Modifier.height(24.dp))

        val actions = buildList<@Composable (Position) -> Unit> {
            add { position ->
                GroupedRow(
                    title = stringResource(R.string.accounts_last_sync),
                    summary = syncState(shown),
                    position = position,
                    leading = { Icon(Icons.Rounded.History, contentDescription = null) },
                )
            }
            if (!usable) return@buildList
            push?.let { pushState ->
                add { position ->
                    GroupedRow(
                        title = stringResource(R.string.accounts_push),
                        summary = accountPushSummary(pushState, shown.lists),
                        position = position,
                        leading = { Icon(Icons.Rounded.Bolt, contentDescription = null) },
                    )
                }
            }
            if (shown.needsSignIn) {
                add { position ->
                    GroupedRow(
                        title = stringResource(R.string.accounts_sign_in_again),
                        position = position,
                        leading = { Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null) },
                        onClick = { onSignInAgain(account) },
                    )
                }
            } else {
                add { position ->
                    GroupedRow(
                        title = stringResource(R.string.accounts_sync_now),
                        position = position,
                        leading = { Icon(Icons.Rounded.CloudSync, contentDescription = null) },
                        onClick = { viewModel.syncNow(account) },
                    )
                }
                add { position ->
                    GroupedRow(
                        title = stringResource(R.string.accounts_lists),
                        summary = stringResource(R.string.accounts_lists_summary),
                        position = position,
                        leading = { Icon(Icons.AutoMirrored.Rounded.List, contentDescription = null) },
                        onClick = { viewModel.openLists(account.id) },
                    )
                }
            }
        }
        actions.forEachIndexed { index, action -> action(positionOf(index, actions.size)) }

        if (!usable) {
            Spacer(Modifier.height(24.dp))
            ExternalStorageNotice(onOpenStorage)
        }

        // ⚠️ Above the removal, below the state — because it is the one thing on
        // this screen the user did not already know. Conflicts are server-wins,
        // and this group is the other half of it:
        // without it a discarded edit is indistinguishable from lost work.
        if (shown.notices.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SyncNotices(
                notices = shown.notices,
                onDismiss = { viewModel.dismissNotices(account) },
                onRetry = viewModel::retry,
            )
        }

        Spacer(Modifier.height(24.dp))

        // Its own group, away from the things that are safe to press twice.
        //
        // ⚠️ Nothing here shows the removal in flight, deliberately. Confirming
        // hands off to `onRemoved`, which puts this screen away in the same
        // frame — so a pending state drawn here could never appear, and the
        // dimming and spinner that were written for it were dead code claiming
        // to prevent a second tap that cannot happen. The wait is visible where
        // the user actually ends up: the row on `AccountsScreen`.
        GroupedRow(
            title = errorTitle(stringResource(R.string.accounts_remove)),
            position = Position.Alone,
            leading = {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = { confirming = true },
        )
        Spacer(Modifier.height(24.dp))
    }

    val lists by viewModel.lists.collectAsStateWithLifecycle()
    lists?.let { state ->
        SyncedListsPicker(state = state, viewModel = viewModel)
    }

    if (confirming) {
        RemoveAccountPicker(
            identity = identity,
            onDismiss = { confirming = false },
            onRemove = { deleteLocalData ->
                viewModel.remove(account, deleteLocalData)
                confirming = false
                onRemoved()
            },
        )
    }
}

/**
 * What the last syncs replaced or gave up on, one row each.
 *
 * Named, never counted. "3 edits were replaced" leaves the user to work out
 * which three across every list they own, which is the same as not telling them
 * — so each row carries the task's own title, the list it lives in, and the
 * reason, and "Got it" is what clears them.
 */
@Composable
private fun SyncNotices(
    notices: List<SyncNotice>,
    onDismiss: () -> Unit,
    onRetry: (SyncNotice) -> Unit,
) {
    Text(
        stringResource(R.string.sync_notices_title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = GroupedListInset, vertical = 8.dp),
    )
    // The dismissal is a row of the same group: it is the last thing you do to
    // this list, and a floating button beside it would read as belonging to the
    // screen rather than to these notices.
    val rows = notices.size + 1
    notices.forEachIndexed { index, notice ->
        GroupedRow(
            title = notice.subject.ifBlank { stringResource(R.string.task_untitled) },
            summary = stringResource(
                R.string.accounts_summary,
                notice.listName,
                stringResource(notice.cause.message),
            ),
            position = positionOf(index, rows),
            leading = {
                Icon(
                    if (notice.kind == SyncNotice.Kind.QUARANTINED) {
                        Icons.Rounded.SyncProblem
                    } else {
                        Icons.Rounded.HistoryToggleOff
                    },
                    contentDescription = null,
                )
            },
            trailing = if (notice.kind == SyncNotice.Kind.QUARANTINED) {
                {
                    IconButton(onClick = { onRetry(notice) }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.sync_notice_retry),
                        )
                    }
                }
            } else {
                null
            },
        )
    }
    GroupedRow(
        title = stringResource(R.string.sync_notices_dismiss),
        position = positionOf(notices.size, rows),
        leading = { Icon(Icons.Rounded.Check, contentDescription = null) },
        onClick = onDismiss,
    )
}

/**
 * Why an edit is gone, in words rather than an enum name.
 *
 * A quarantine has no cause of its own — nothing chose it, a resource simply
 * kept failing — so the null branch is the sentence for that, not a fallback.
 */
private val DiscardedEdit.Cause?.message: Int
    get() = when (this) {
        DiscardedEdit.Cause.SERVER_NEWER -> R.string.sync_notice_cause_server_newer
        DiscardedEdit.Cause.DELETED_ON_SERVER -> R.string.sync_notice_cause_deleted_on_server
        DiscardedEdit.Cause.DELETE_LOST -> R.string.sync_notice_cause_delete_lost
        null -> R.string.sync_notice_cause_quarantined
    }

/** The account's logo at full size, over the two things the title bar left out. */
@Composable
private fun AccountHero(identity: AccountIdentity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GroupedListInset),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProviderLogo(identity.provider, size = 72.dp)
        Spacer(Modifier.height(12.dp))
        identity.user?.let {
            Text(it, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            identity.secondary ?: stringResource(R.string.accounts_generic_provider),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Removing an account is two different things — the lists stay behind as
 * device-only lists, or they go with it — so it is a browse-style choice, and
 * those are full-screen. Each row says what it does and does it; leaving without
 * choosing is back.
 */
@Composable
private fun RemoveAccountPicker(
    identity: AccountIdentity,
    onDismiss: () -> Unit,
    onRemove: (deleteLocalData: Boolean) -> Unit,
) {
    FullScreenPicker(
        title = stringResource(R.string.accounts_remove),
        onDismiss = onDismiss,
        predictiveBack = true,
    ) {
        Text(
            stringResource(R.string.accounts_remove_body, identity.title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = GroupedListInset),
        )
        Spacer(Modifier.height(16.dp))
        GroupedRow(
            title = stringResource(R.string.accounts_remove_keep),
            summary = stringResource(R.string.accounts_remove_keep_body),
            position = Position.Top,
            leading = { Icon(Icons.Rounded.Inventory2, contentDescription = null) },
            onClick = { onRemove(false) },
        )
        GroupedRow(
            title = errorTitle(stringResource(R.string.accounts_remove_wipe)),
            summary = AnnotatedString(stringResource(R.string.accounts_remove_wipe_body)),
            position = Position.Bottom,
            leading = {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = { onRemove(true) },
        )
    }
}

/**
 * Which of the account's collections sync, re-read from the server — so lists
 * made there after setup can be added, and synced ones dropped.
 */
@Composable
private fun SyncedListsPicker(state: AccountsViewModel.ListsState, viewModel: AccountsViewModel) {
    var confirmingDrop by remember { mutableStateOf(false) }
    val ready = state as? AccountsViewModel.ListsState.Ready

    FullScreenPicker(
        title = stringResource(R.string.accounts_lists),
        onDismiss = viewModel::closeLists,
        predictiveBack = true,
        actions = {
            if (ready != null) {
                Button(
                    onClick = {
                        if (ready.dropping) confirmingDrop = true else viewModel.saveLists(keepUnticked = true)
                    },
                    modifier = Modifier.padding(end = 12.dp),
                ) { Text(stringResource(R.string.save)) }
            }
        },
    ) {
        when (state) {
            AccountsViewModel.ListsState.Loading -> Row(
                modifier = Modifier.padding(horizontal = GroupedListInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.accounts_lists_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AccountsViewModel.ListsState.NeedsSignIn -> PickerNote(stringResource(R.string.accounts_needs_sign_in))

            is AccountsViewModel.ListsState.Failed -> PickerNote(
                stringResource(state.cause?.message ?: R.string.add_account_error_server),
            )

            is AccountsViewModel.ListsState.Ready -> state.collections.forEachIndexed { index, collection ->
                val checked = collection.url in state.selected
                GroupedRow(
                    title = collection.displayName ?: collection.url.encodedPath,
                    summary = when {
                        collection.readOnly -> stringResource(R.string.add_account_lists_read_only)
                        collection.isShared -> stringResource(R.string.add_account_lists_shared)
                        else -> null
                    },
                    position = positionOf(index, state.collections.size),
                    selected = checked,
                    trailing = { if (checked) SelectedCheck() },
                    onClick = { viewModel.toggleList(collection.url) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmingDrop) {
        DropListsPicker(
            onDismiss = { confirmingDrop = false },
            onChoose = { keep ->
                confirmingDrop = false
                viewModel.saveLists(keepUnticked = keep)
            },
        )
    }
}

@Composable
private fun PickerNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = GroupedListInset),
    )
}

/** What happens to the tasks of lists that stop syncing — the same two ways removing an account offers. */
@Composable
private fun DropListsPicker(onDismiss: () -> Unit, onChoose: (keep: Boolean) -> Unit) {
    FullScreenPicker(
        title = stringResource(R.string.accounts_lists_drop_title),
        onDismiss = onDismiss,
        predictiveBack = true,
    ) {
        PickerNote(stringResource(R.string.accounts_lists_drop_body))
        Spacer(Modifier.height(16.dp))
        GroupedRow(
            title = stringResource(R.string.accounts_lists_drop_keep),
            summary = stringResource(R.string.accounts_lists_drop_keep_body),
            position = Position.Top,
            leading = { Icon(Icons.Rounded.Inventory2, contentDescription = null) },
            onClick = { onChoose(true) },
        )
        GroupedRow(
            title = errorTitle(stringResource(R.string.accounts_lists_drop_wipe)),
            summary = AnnotatedString(stringResource(R.string.accounts_lists_drop_wipe_body)),
            position = Position.Bottom,
            leading = {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = { onChoose(false) },
        )
    }
}

/** A destructive row's title, in the error colour the row itself cannot take. */
@Composable
private fun errorTitle(text: String): AnnotatedString {
    val error = MaterialTheme.colorScheme.error
    return remember(text, error) {
        buildAnnotatedString { withStyle(SpanStyle(color = error)) { append(text) } }
    }
}

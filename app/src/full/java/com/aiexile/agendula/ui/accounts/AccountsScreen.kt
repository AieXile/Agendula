package com.aiexile.agendula.ui.accounts

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiexile.agendula.R
import com.aiexile.agendula.data.prefs.SYNC_INTERVAL_PRESETS
import com.aiexile.agendula.data.sync.SyncFailure
import com.aiexile.agendula.data.tasks.room.AccountEntity
import com.aiexile.agendula.ui.common.OnResume
import com.aiexile.agendula.ui.settings.SettingsHint
import de.jeanlucmakiola.floret.components.CollapsingScaffold
import de.jeanlucmakiola.floret.components.GroupedListInset
import de.jeanlucmakiola.floret.components.GroupedRow
import de.jeanlucmakiola.floret.components.OptionPicker
import de.jeanlucmakiola.floret.components.Position
import de.jeanlucmakiola.floret.components.positionOf

/** The CalDAV accounts this device syncs with. */
@Composable
internal fun AccountsScreen(
    onAddAccount: () -> Unit,
    onSignInAgain: (AccountEntity) -> Unit,
    onOpenAccount: (Long) -> Unit,
    onOpenStorage: () -> Unit,
    onBack: () -> Unit,
    viewModel: AccountsViewModel,
    visible: Boolean,
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val usable by viewModel.accountsUsable.collectAsStateWithLifecycle()
    val syncInterval by viewModel.syncIntervalMinutes.collectAsStateWithLifecycle()
    val push by viewModel.push.collectAsStateWithLifecycle()
    var showInterval by remember { mutableStateOf(false) }
    var showPush by remember { mutableStateOf(false) }
    OnResume(viewModel::refreshPush)

    CollapsingScaffold(
        title = stringResource(R.string.settings_section_accounts),
        onBack = onBack,
        predictiveBack = true,
        visible = visible,
    ) {
        val loaded = accounts ?: return@CollapsingScaffold

        if (loaded.isEmpty()) {
            Icon(
                Icons.Rounded.CloudSync,
                contentDescription = null,
                modifier = Modifier.padding(horizontal = GroupedListInset),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.accounts_empty_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = GroupedListInset),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.accounts_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = GroupedListInset),
            )
            Spacer(Modifier.height(24.dp))
        } else {
            loaded.forEachIndexed { index, row ->
                val account = row.account
                val identity = account.identity()
                GroupedRow(
                    title = identity.title,
                    // One line, so it says the most useful thing it can. A row
                    // on its way out has one piece of news; an account holding
                    // unread sync reports has another, and either outranks
                    // "synced 5 minutes ago" — but neither outranks a sign-in
                    // that has stopped, which `syncState` already puts first.
                    summary = when {
                        row.removing -> stringResource(R.string.accounts_removing)
                        row.notices.isNotEmpty() && !row.needsSignIn -> accountSummary(
                            identity.user,
                            pluralStringResource(
                                R.plurals.accounts_notices,
                                row.notices.size,
                                row.notices.size,
                            ),
                        )
                        else -> accountSummary(identity.user, syncState(row))
                    },
                    position = positionOf(index, loaded.size),
                    // A row whose account is on its way out is not a row you can
                    // act on, so it stops looking like one: dimmed, its action
                    // replaced by the progress, and nothing to tap.
                    dimmed = row.removing,
                    leading = { ProviderLogo(identity.provider) },
                    trailing = {
                        when {
                            row.removing -> CircularProgressIndicator(Modifier.size(20.dp))
                            !usable -> Unit
                            row.needsSignIn -> IconButton(onClick = { onSignInAgain(account) }) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Login,
                                    contentDescription = stringResource(R.string.accounts_sign_in_again),
                                )
                            }
                            else -> IconButton(onClick = { viewModel.syncNow(account) }) {
                                Icon(
                                    Icons.Rounded.Sync,
                                    contentDescription = stringResource(R.string.accounts_sync_now),
                                )
                            }
                        }
                    },
                    onClick = { onOpenAccount(account.id) }.takeUnless { row.removing },
                )
            }
            Spacer(Modifier.height(24.dp))
            if (usable) {
                val pushState = push
                GroupedRow(
                    title = stringResource(R.string.accounts_sync_interval),
                    summary = syncIntervalLabel(syncInterval),
                    position = if (pushState == null) Position.Alone else Position.Top,
                    onClick = { showInterval = true },
                )
                if (pushState != null) {
                    GroupedRow(
                        title = stringResource(R.string.accounts_push),
                        summary = pushSummary(pushState),
                        position = Position.Bottom,
                        onClick = { showPush = true },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        if (usable) {
            GroupedRow(
                title = stringResource(R.string.accounts_add),
                position = Position.Alone,
                leading = { Icon(Icons.Rounded.Add, contentDescription = null) },
                onClick = onAddAccount,
            )
        } else {
            ExternalStorageNotice(onOpenStorage)
        }
    }

    if (showInterval) {
        OptionPicker(
            title = stringResource(R.string.accounts_sync_interval),
            options = SYNC_INTERVAL_PRESETS,
            selected = syncInterval,
            label = { syncIntervalLabel(it) },
            header = {
                SettingsHint(stringResource(R.string.accounts_sync_interval_hint))
                Spacer(Modifier.height(8.dp))
            },
            onSelect = viewModel::setSyncInterval,
            onDismiss = { showInterval = false },
        )
    }

    val pushState = push
    if (showPush && pushState != null) {
        PushPicker(
            push = pushState,
            onSelect = viewModel::choosePushDistributor,
            onDismiss = { showPush = false },
        )
    }
}

@Composable
private fun PushPicker(
    push: AccountsViewModel.PushUi,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionPicker(
        title = stringResource(R.string.accounts_push),
        options = listOf(PUSH_OFF) + push.installed.map { it.packageName },
        // Nothing is ticked while push is on but no distributor is chosen yet.
        selected = if (push.enabled) push.current?.packageName else PUSH_OFF,
        label = { option ->
            if (option == PUSH_OFF) {
                stringResource(R.string.accounts_push_off)
            } else {
                push.installed.first { it.packageName == option }.label
            }
        },
        header = {
            SettingsHint(stringResource(R.string.accounts_push_hint))
            if (push.installed.isEmpty()) SettingsHint(stringResource(R.string.accounts_push_none_installed))
            Spacer(Modifier.height(8.dp))
        },
        onSelect = { option -> onSelect(option.takeUnless { it == PUSH_OFF }) },
        onDismiss = onDismiss,
    )
}

/** The picker's "Off" row, which no package can be named. */
private const val PUSH_OFF = ""

@Composable
internal fun pushSummary(push: AccountsViewModel.PushUi): String {
    val current = push.current
    val lists = push.lists
    return when {
        !push.enabled -> stringResource(R.string.accounts_push_off)
        push.needsChoice -> stringResource(R.string.accounts_push_choose)
        current == null -> stringResource(R.string.accounts_push_no_distributor)
        lists.capable == 0 -> stringResource(R.string.accounts_push_unsupported)
        lists.subscribed == 0 -> stringResource(R.string.accounts_push_pending)
        lists.subscribed < lists.synced -> pluralStringResource(
            R.plurals.accounts_push_partial,
            lists.synced,
            lists.subscribed,
            lists.synced,
            current.label,
        )
        else -> stringResource(R.string.accounts_push_via, current.label)
    }
}

/** The same, for one account's lists. */
@Composable
internal fun accountPushSummary(push: AccountsViewModel.PushUi, lists: AccountsViewModel.ListPush): String =
    when {
        !push.enabled -> stringResource(R.string.accounts_push_off)
        push.needsChoice -> stringResource(R.string.accounts_push_choose)
        push.current == null -> stringResource(R.string.accounts_push_no_distributor)
        lists.capable == 0 -> stringResource(R.string.accounts_push_account_unsupported)
        lists.subscribed == 0 -> stringResource(R.string.accounts_push_pending)
        lists.subscribed < lists.synced -> pluralStringResource(
            R.plurals.accounts_push_account_partial,
            lists.synced,
            lists.subscribed,
            lists.synced,
        )
        else -> stringResource(R.string.accounts_push_account_on)
    }

@Composable
private fun syncIntervalLabel(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.accounts_sync_interval_manual)
    minutes % 60 == 0 -> pluralStringResource(R.plurals.accounts_sync_every_hours, minutes / 60, minutes / 60)
    else -> pluralStringResource(R.plurals.accounts_sync_every_minutes, minutes, minutes)
}

/** In External mode accounts stay, but sync into a store nothing is showing. */
@Composable
internal fun ExternalStorageNotice(onOpenStorage: () -> Unit) {
    GroupedRow(
        title = stringResource(R.string.accounts_external_storage_title),
        summary = stringResource(R.string.accounts_external_storage),
        position = Position.Alone,
        leading = { Icon(Icons.Rounded.Storage, contentDescription = null) },
        onClick = onOpenStorage,
    )
}

/**
 * Never the raw values: `lastSyncError` is an exception string and `lastSyncAt`
 * renders as an ISO-8601 UTC instant, and both bypass `strings.xml` entirely.
 */
@Composable
internal fun syncState(row: AccountsViewModel.AccountRow): String = when {
    // Distinct from a failed sync on purpose: this one has stopped retrying, and
    // only the user can restart it.
    row.needsSignIn -> stringResource(R.string.accounts_needs_sign_in)
    row.account.lastSyncError != null -> failureText(SyncFailure.of(row.account.lastSyncError))
    row.account.lastSyncAt != null -> DateUtils.getRelativeTimeSpanString(
        row.account.lastSyncAt.toEpochMilliseconds(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

    else -> stringResource(R.string.accounts_never_synced)
}

/** Why the last sync failed, by class — never the engine's own words. */
@Composable
private fun failureText(failure: SyncFailure): String = when (failure.kind) {
    SyncFailure.Kind.SIGN_IN -> stringResource(R.string.accounts_failed_sign_in)
    SyncFailure.Kind.UNREACHABLE -> stringResource(R.string.accounts_failed_unreachable)
    SyncFailure.Kind.CERTIFICATE -> stringResource(R.string.accounts_failed_certificate)
    SyncFailure.Kind.SERVER -> failure.httpCode
        ?.let { stringResource(R.string.accounts_failed_server_code, it) }
        ?: stringResource(R.string.accounts_failed_server)
    SyncFailure.Kind.MISCONFIGURED -> stringResource(R.string.accounts_failed_misconfigured)
    SyncFailure.Kind.OTHER -> stringResource(R.string.accounts_sync_failed)
}

/** Who, then how it last went — the two things the title does not already say. */
@Composable
private fun accountSummary(user: String?, state: String): String =
    if (user == null) state else stringResource(R.string.accounts_summary, user, state)

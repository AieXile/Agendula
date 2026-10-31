package com.aiexile.agendula.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aiexile.agendula.R

/**
 * A task list's account as a screen names it.
 *
 * [com.aiexile.agendula.domain.TaskList.accountName] is a data label: a device-only
 * store carries the OpenTasks contract's `Local` placeholder, which is not prose to
 * put in front of the user. Screens swap that one label for a localized name; a
 * synced account keeps the name its server gave it.
 */
@Composable
internal fun accountLabel(accountName: String, isLocal: Boolean): String =
    if (isLocal) stringResource(R.string.account_local) else accountName

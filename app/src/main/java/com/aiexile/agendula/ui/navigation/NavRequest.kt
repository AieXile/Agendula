package com.aiexile.agendula.ui.navigation

import android.net.Uri
import com.aiexile.agendula.domain.SmartList

/**
 * A navigation something outside the app asked for — a notification tap.
 * Parsed from the launch intent in `MainActivity` and consumed once by
 * [AgendulaNavHost], which pushes it over whatever is showing.
 */
sealed interface NavRequest {
    /** One task's detail; [occurrenceStart] (epoch millis) picks the occurrence of a series. */
    data class OpenTask(val taskId: Long, val occurrenceStart: Long? = null) : NavRequest

    /** Settings → Accounts, where a sync report's detail lives. */
    data object OpenAccounts : NavRequest

    /** One account's detail — where "sign in again" lives. */
    data class OpenAccount(val accountId: Long) : NavRequest

    /**
     * A new task, optionally titled with text shared from another app, or
     * preset to a list — a widget's own add button, when it shows one list.
     */
    data class NewTask(val title: String? = null, val listId: Long? = null) : NavRequest

    /** One of the smart lists — the launcher's "Today" shortcut, a widget header. */
    data class OpenSmart(val list: SmartList) : NavRequest

    /** One real list — a widget header. */
    data class OpenList(val listId: Long) : NavRequest

    /** One saved filter — a widget header. */
    data class OpenFilter(val filterId: Long) : NavRequest

    /** An `.ics` another app handed over. */
    data class Import(val uri: Uri) : NavRequest
}

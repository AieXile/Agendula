package com.aiexile.agendula.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Rows ticked in a widget but not yet completed in the store. Widgets can't
 * animate, so [CompleteTaskAction] shows the row as done for a beat before
 * completing it — every widget drawing that row reads this to render the
 * ticked frame.
 */
internal object WidgetCompletions {
    private val keys = MutableStateFlow<Set<String>>(emptySet())

    fun key(taskId: Long, occurrenceStart: Long?): String = "$taskId@${occurrenceStart ?: ""}"

    /** Marks [key] as completing; `false` if it already was (a double tap). */
    fun start(key: String): Boolean {
        var added = false
        keys.update { current -> if (key in current) current else current + key.also { added = true } }
        return added
    }

    fun finish(key: String) = keys.update { it - key }

    @Composable
    fun isCompleting(row: WidgetTaskRow): Boolean =
        key(row.taskId, row.occurrenceStart?.toEpochMilliseconds()) in keys.collectAsState().value
}

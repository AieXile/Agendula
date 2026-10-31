package com.aiexile.agendula.widget

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aiexile.agendula.data.di.ApplicationScope
import com.aiexile.agendula.data.tasks.TasksDataSource
import de.jeanlucmakiola.floret.glance.WidgetRollover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the task widget in step with the store: any change — our own writes,
 * a sync, a storage-mode switch — schedules one debounced redraw. Started once
 * from `AgendulaApp`, after the startup gate, so it observes the right store.
 */
@Singleton
class TaskWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataSource: TasksDataSource,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var observer: AutoCloseable? = null

    @OptIn(FlowPreview::class)
    fun start() {
        if (observer != null) return
        observer = runCatching { dataSource.registerObserver { requests.trySend(Unit) } }.getOrNull()
        scope.launch(Dispatchers.IO) {
            requests.receiveAsFlow().debounce(DEBOUNCE_MILLIS).collect { redraw() }
        }
        scope.launch(Dispatchers.IO) {
            runCatching { WidgetRollover.sync(context, WIDGET_RECEIVER_CLASSES, ::widgetRolloverPendingIntent) }
        }
        // The process may have been started by a change it never heard about.
        requestRefresh()
    }

    /** Redraw soon; bursts collapse into one pass. */
    fun requestRefresh() {
        requests.trySend(Unit)
    }

    private suspend fun redraw() {
        runCatching {
            if (!WidgetRollover.hasPlacedWidgets(context, WIDGET_RECEIVER_CLASSES)) return@runCatching
            updateAllWidgets(context)
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 500L
    }
}

package de.jeanlucmakiola.agendula.widget

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository

/**
 * Hilt bridge for the Glance widget, which the framework instantiates and so
 * cannot take constructor injection. Reads the same repository the app does.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun tasksRepository(): TasksRepository
    fun settingsPrefs(): SettingsPrefs
}

internal fun Context.widgetEntryPoint(): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)

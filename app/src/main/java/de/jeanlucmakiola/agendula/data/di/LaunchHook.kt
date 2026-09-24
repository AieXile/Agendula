package de.jeanlucmakiola.agendula.data.di

import android.content.Intent
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Something a build variant wants to run when the app is launched with [Intent].
 * Release builds contribute none; the debug source set adds the demo seeder, so
 * that class never ships.
 */
fun interface LaunchHook {
    suspend fun onLaunch(intent: Intent)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LaunchHookModule {
    @Multibinds
    abstract fun launchHooks(): Set<LaunchHook>
}

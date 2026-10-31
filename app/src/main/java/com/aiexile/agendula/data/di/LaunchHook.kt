package com.aiexile.agendula.data.di

import android.content.Intent
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Something a build variant runs on a genuine app open (not on a configuration
 * change): sync in the `full` flavor, the demo seeder in debug builds.
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

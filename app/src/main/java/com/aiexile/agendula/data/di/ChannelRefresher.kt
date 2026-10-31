package com.aiexile.agendula.data.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * A notification channel a build variant adds, re-created in the current
 * language on start and on a configuration change. The `full` flavor
 * contributes the sync notices; `offline` has none.
 */
fun interface ChannelRefresher {
    fun refreshChannel()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ChannelRefresherModule {
    @Multibinds
    abstract fun channelRefreshers(): Set<ChannelRefresher>
}

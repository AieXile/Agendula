package com.aiexile.agendula.data.sync

import com.aiexile.agendula.data.prefs.SettingsPrefs
import com.aiexile.agendula.data.tasks.ProviderResolver
import com.aiexile.agendula.data.tasks.StorageMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether CalDAV accounts can do anything right now.
 *
 * Sync writes into Agendula's own store. In External mode the screens read a
 * third-party provider instead, so an account would sync into rows nobody sees.
 * Read from the stored preference rather than [ProviderResolver.mode], which is
 * only current once `StorageModeHolder` has mirrored it — and a worker can start
 * before that.
 */
@Singleton
class SyncAvailability @Inject constructor(
    private val prefs: SettingsPrefs,
    private val resolver: ProviderResolver,
) {

    suspend fun accountsUsable(): Boolean = observe().first()

    fun observe(): Flow<Boolean> = prefs.storageMode.map { stored ->
        (stored ?: resolver.autoMode()) == StorageMode.OWN
    }
}

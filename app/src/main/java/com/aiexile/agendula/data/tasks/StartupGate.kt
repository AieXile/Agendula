package com.aiexile.agendula.data.tasks

import com.aiexile.agendula.data.di.ApplicationScope
import com.aiexile.agendula.data.tasks.legacy.OneShotImport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The work that has to finish before anything reads a task store: the stored
 * [StorageMode] has to reach [ProviderResolver], and a v0.3.x install's tasks
 * have to be imported out of the dmfs provider's file into Room.
 *
 * Both are startup races with the same shape. Reading before the mode lands
 * answers from `autoMode()` instead of the user's choice; reading before the
 * import lands shows an upgrading user an empty app, which is the single worst
 * thing this migration could do.
 */
@Singleton
class StartupGate @Inject constructor(
    private val storageModeHolder: StorageModeHolder,
    private val oneShotImport: OneShotImport,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val ready = CompletableDeferred<Unit>()

    /** Call once, from `Application.onCreate`. */
    fun start() {
        storageModeHolder.start()
        scope.launch {
            // Opens the gate even on failure: a store that cannot be imported is
            // still better shown empty than not shown at all, and the source file
            // is left where it was either way.
            runCatching {
                storageModeHolder.awaitReady()
                oneShotImport.runIfNeeded()
            }
            ready.complete(Unit)
        }
    }

    suspend fun awaitReady() = ready.await()
}

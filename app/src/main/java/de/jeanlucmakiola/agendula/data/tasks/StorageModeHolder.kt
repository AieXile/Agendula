package de.jeanlucmakiola.agendula.data.tasks

import de.jeanlucmakiola.agendula.data.di.ApplicationScope
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the stored [StorageMode] into [ProviderResolver].
 *
 * The resolver is consulted synchronously from every data-source call and from
 * `providerStatus()` on the main thread, so it cannot read DataStore itself.
 * This is the one component that bridges the two: it collects the preference for
 * the life of the process and pushes each value across.
 *
 * [awaitReady] exists for the startup race. Until the first DataStore emission
 * arrives the resolver's mode is `null` and `ProviderResolver.autoMode` answers
 * instead — fine as a steady state, wrong for a user who explicitly chose the
 * other mode. Anything that touches the provider before the UI is up (the launch
 * reminder re-sync, notably) should wait rather than risk reading the wrong
 * store and rescheduling every alarm off it.
 */
@Singleton
class StorageModeHolder @Inject constructor(
    private val prefs: SettingsPrefs,
    private val resolver: ProviderResolver,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val firstValue = CompletableDeferred<Unit>()

    /** Starts mirroring. Idempotent in effect; call once, from `Application.onCreate`. */
    fun start() {
        scope.launch {
            try {
                prefs.storageMode.collect { mode ->
                    resolver.storageMode = mode
                    firstValue.complete(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // ⚠️ Two failures in one, and the second is the worse. This is a
                // root coroutine in a scope with no exception handler, so a read
                // that throws — a corrupt file the handler could not replace, a
                // filesystem that will not answer — takes the app down at every
                // launch. And releasing the gate is not optional either:
                // `awaitReady` is awaited before anything touches the provider,
                // so failing quietly without it parks every observing flow for
                // ever, which is a blank app rather than a crashing one.
                // `autoMode` answers from here on, which is the steady state for
                // a user who never chose.
                firstValue.complete(Unit)
            }
        }
    }

    /** Suspends until the stored mode has been applied at least once. */
    suspend fun awaitReady() = firstValue.await()
}

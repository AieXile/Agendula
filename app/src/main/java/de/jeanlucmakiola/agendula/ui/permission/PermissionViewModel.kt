package de.jeanlucmakiola.agendula.ui.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import de.jeanlucmakiola.agendula.data.tasks.ProviderResolver
import de.jeanlucmakiola.agendula.data.tasks.ProviderStatus
import de.jeanlucmakiola.agendula.data.tasks.StorageMode
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PermissionUiState(
    val status: ProviderStatus = ProviderStatus.NO_PROVIDER,
    /** The active provider's runtime permissions to request, when one is present. */
    val permissionsToRequest: List<String> = emptyList(),
)

/**
 * Gates app entry: is a tasks provider installed, and do we hold its permissions?
 * The Composable owns the actual permission-launcher and store intents; this VM
 * supplies the [status] and the exact permission strings to ask for.
 *
 * In the default Own mode this gate never appears at all — the store is our own
 * Room database, so there is nothing to install and nothing to grant. It exists
 * for External mode, which also makes it the only screen an External user can
 * reach once their provider app stops answering: hence [useOwnStore].
 */
@HiltViewModel
class PermissionViewModel @Inject constructor(
    private val repository: TasksRepository,
    private val providerResolver: ProviderResolver,
    private val prefs: SettingsPrefs,
) : ViewModel() {

    private val refreshes = MutableStateFlow(0)

    // Re-evaluated when the *resolver's* mode lands, not when the preference is
    // written: anything read in between still answers for the store we just left.
    // Conflated, as everywhere else this signal is bridged: only the latest mode
    // matters, and a full buffer drops it rather than the ones it supersedes.
    private val modeChanges: Flow<Unit> = callbackFlow {
        trySend(Unit)
        val handle = providerResolver.onModeChanged { trySend(Unit) }
        awaitClose { handle.close() }
    }.buffer(Channel.CONFLATED)

    val state: StateFlow<PermissionUiState> =
        combine(refreshes, modeChanges) { _, _ -> currentState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), currentState())

    /** Re-read provider + permission state (call after returning from a request). */
    fun refresh() = refreshes.update { it + 1 }

    /**
     * Leave a store this device can no longer read. The provider app can be
     * uninstalled, or its permission revoked, after External was chosen — and the
     * gate is then the only screen reachable, Settings included. Our own store
     * always reads, so it is the way out.
     */
    fun useOwnStore() = viewModelScope.launch { prefs.setStorageMode(StorageMode.OWN) }

    private fun currentState(): PermissionUiState {
        val provider = providerResolver.resolve()
        return PermissionUiState(
            status = repository.providerStatus(),
            // Null in OWN mode, where there is no provider and nothing to grant.
            permissionsToRequest = provider
                ?.let { listOf(it.readPermission, it.writePermission) }
                .orEmpty(),
        )
    }
}

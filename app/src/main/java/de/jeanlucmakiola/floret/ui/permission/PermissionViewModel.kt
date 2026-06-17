package de.jeanlucmakiola.floret.ui.permission

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.floret.data.tasks.ProviderResolver
import de.jeanlucmakiola.floret.data.tasks.ProviderStatus
import de.jeanlucmakiola.floret.data.tasks.TasksRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
@HiltViewModel
class PermissionViewModel @Inject constructor(
    private val repository: TasksRepository,
    private val providerResolver: ProviderResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(PermissionUiState())
    val state: StateFlow<PermissionUiState> = _state.asStateFlow()

    init { refresh() }

    /** Re-read provider + permission state (call after returning from a request). */
    fun refresh() {
        val provider = providerResolver.resolve()
        _state.value = PermissionUiState(
            status = repository.providerStatus(),
            permissionsToRequest = provider
                ?.let { listOf(it.readPermission, it.writePermission) }
                .orEmpty(),
        )
    }
}

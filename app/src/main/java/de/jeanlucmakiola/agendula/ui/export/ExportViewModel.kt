package de.jeanlucmakiola.agendula.ui.export

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.agendula.data.export.ExportFailedException
import de.jeanlucmakiola.agendula.data.export.ExportFailure
import de.jeanlucmakiola.agendula.data.export.ExportResult
import de.jeanlucmakiola.agendula.data.export.ExportWriter
import de.jeanlucmakiola.agendula.data.export.TaskExporter
import de.jeanlucmakiola.agendula.data.tasks.ProviderResolver
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.agendula.data.tasks.recoveringFromProviderFailure
import de.jeanlucmakiola.agendula.domain.TaskList
import de.jeanlucmakiola.agendula.domain.export.ExportDocument
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where an export was aimed: the SAF `Uri` the user picked and whether it was a
 * folder or a single zip. Kept on the outcome so the receipt can name the place
 * and offer to open it — the `Uri` is the only handle we have on it.
 */
data class ExportDestination(val uri: Uri, val isFolder: Boolean)

/** How the last export ended, kept on screen rather than flashed past. */
sealed interface ExportOutcome {
    val destination: ExportDestination

    data class Success(
        val fileCount: Int,
        override val destination: ExportDestination,
    ) : ExportOutcome

    data class Failure(
        val reason: ExportFailure,
        override val destination: ExportDestination,
    ) : ExportOutcome
}

data class ExportUiState(
    val lists: List<TaskList> = emptyList(),
    /** Lists the user has ticked *off*; everything else is included. */
    val excluded: Set<Long> = emptySet(),
    val running: Boolean = false,
    val outcome: ExportOutcome? = null,
) {
    fun isSelected(listId: Long): Boolean = listId !in excluded

    val selectedCount: Int get() = lists.count { isSelected(it.id) }
}

/**
 * Drives the export screen. Holds the selection as an exclusion set so a list
 * that appears while the screen is open is exported too — the natural reading of
 * "everything, minus what I unticked".
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    repository: TasksRepository,
    resolver: ProviderResolver,
    private val exporter: TaskExporter,
    private val writer: ExportWriter,
) : ViewModel() {

    private val excluded = MutableStateFlow(emptySet<Long>())
    private val running = MutableStateFlow(false)
    private val outcome = MutableStateFlow<ExportOutcome?>(null)

    private var exportJob: Job? = null

    // List ids are per-store, and Settings can switch stores with this ViewModel
    // still alive — so the selection, the receipt and a write already addressing
    // the old store's lists all go with it.
    private val modeHandle = resolver.onModeChanged {
        exportJob?.cancel()
        excluded.value = emptySet()
        outcome.value = null
    }

    override fun onCleared() {
        modeHandle.close()
    }

    val state: StateFlow<ExportUiState> =
        combine(
            repository.taskLists().recoveringFromProviderFailure { emptyList() },
            excluded,
            running,
            outcome,
        ) { lists, excluded, running, outcome ->
            ExportUiState(lists, excluded, running, outcome)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportUiState())

    fun toggle(listId: Long) = excluded.update { current ->
        if (listId in current) current - listId else current + listId
    }

    /** Writes one `.ics` per list into a folder the user picked through SAF. */
    fun exportToFolder(tree: Uri) = export(ExportDestination(tree, isFolder = true)) { documents ->
        writer.writeToTree(tree, documents)
    }

    /** Writes every list into a single zip the user named through SAF. */
    fun exportToZip(target: Uri) = export(ExportDestination(target, isFolder = false)) { documents ->
        writer.writeZip(target, documents)
    }

    private fun export(
        destination: ExportDestination,
        write: suspend (List<ExportDocument>) -> ExportResult,
    ) {
        if (running.value) return
        running.value = true
        outcome.value = null
        exportJob = viewModelScope.launch {
            // Null means "every list" to the exporter, and is what an untouched
            // screen should send: the flow may not have emitted a list yet.
            val selection = excluded.value.takeIf { it.isNotEmpty() }
                ?.let { skipped -> state.value.lists.map { it.id }.toSet() - skipped }
            try {
                val result = write(exporter.export(selection))
                outcome.value = ExportOutcome.Success(result.fileCount, destination)
            } catch (cancelled: CancellationException) {
                // Leaving the screen mid-write is not a failed export.
                throw cancelled
            } catch (error: Exception) {
                outcome.value = ExportOutcome.Failure(
                    (error as? ExportFailedException)?.failure ?: ExportFailure.WRITE_FAILED,
                    destination,
                )
            } finally {
                running.value = false
            }
        }
    }
}

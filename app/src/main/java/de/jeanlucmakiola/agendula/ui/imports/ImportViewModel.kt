package de.jeanlucmakiola.agendula.ui.imports

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jeanlucmakiola.agendula.data.sync.SyncTrigger
import de.jeanlucmakiola.agendula.data.tasks.ProviderResolver
import de.jeanlucmakiola.agendula.data.tasks.StorageMode
import de.jeanlucmakiola.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.agendula.data.tasks.recoveringFromProviderFailure
import de.jeanlucmakiola.agendula.data.tasks.transfer.IcsImport
import de.jeanlucmakiola.agendula.data.tasks.transfer.ImportTarget
import de.jeanlucmakiola.agendula.data.tasks.transfer.ParsedIcs
import de.jeanlucmakiola.agendula.domain.TaskList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** Where the picked tasks go, as the picker offers it. */
sealed interface ImportChoice {
    data class Existing(val list: TaskList) : ImportChoice
    data class NewList(val name: String) : ImportChoice
}

/** The import screen's one step at a time. */
sealed interface ImportStep {
    data object Idle : ImportStep
    data object Reading : ImportStep

    data class Ready(val parsed: ParsedIcs) : ImportStep

    data object Importing : ImportStep

    data class Done(
        val listName: String,
        val imported: Int,
        val skippedExisting: Int,
        val skippedComponents: Int,
    ) : ImportStep

    enum class Problem { UNREADABLE, NO_TASKS, WRITE_FAILED }

    data class Failed(val problem: Problem, val skippedComponents: Int = 0) : ImportStep
}

data class ImportUiState(
    /** Import writes Room rows, so it needs the own store. */
    val ownStore: Boolean = true,
    /** Writable lists, the candidates for a target. */
    val lists: List<TaskList> = emptyList(),
    val step: ImportStep = ImportStep.Idle,
    /** Null until picked: then the new list the file suggests. */
    val choice: ImportChoice? = null,
) {
    /** The effective target: the user's pick, else a new list named after the file. */
    val target: ImportChoice?
        get() {
            val ready = step as? ImportStep.Ready ?: return null
            return choice ?: ImportChoice.NewList(ready.parsed.suggestedListName.orEmpty())
        }
}

/**
 * Drives the `.ics` import: read a picked (or opened) file, let the user choose
 * where it goes, write it, and keep a receipt on screen.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    repository: TasksRepository,
    private val resolver: ProviderResolver,
    private val importer: IcsImport,
    private val syncTrigger: SyncTrigger,
) : ViewModel() {

    private val ownStore = MutableStateFlow(resolver.mode() == StorageMode.OWN)
    private val step = MutableStateFlow<ImportStep>(ImportStep.Idle)
    private val choice = MutableStateFlow<ImportChoice?>(null)
    private var job: Job? = null
    private var lastUri: Uri? = null

    private val modeHandle = resolver.onModeChanged {
        job?.cancel()
        ownStore.value = resolver.mode() == StorageMode.OWN
        step.value = ImportStep.Idle
        choice.value = null
    }

    override fun onCleared() {
        modeHandle.close()
    }

    val state: StateFlow<ImportUiState> =
        combine(
            repository.taskLists().recoveringFromProviderFailure { emptyList() },
            ownStore,
            step,
            choice,
        ) { lists, own, step, choice ->
            ImportUiState(
                ownStore = own,
                lists = lists.filterNot { it.isReadOnly },
                step = step,
                choice = choice,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportUiState(ownStore = ownStore.value))

    /** Reads [uri]; the same uri twice (recomposition, rotation) is read once. */
    fun open(uri: Uri) {
        if (uri == lastUri || !ownStore.value) return
        lastUri = uri
        job?.cancel()
        choice.value = null
        step.value = ImportStep.Reading
        job = viewModelScope.launch {
            val parsed = importer.read(uri)
            step.value = when {
                parsed == null -> ImportStep.Failed(ImportStep.Problem.UNREADABLE)
                parsed.tasks.isEmpty() && parsed.unreadableDocuments > 0 && parsed.skippedComponents == 0 ->
                    ImportStep.Failed(ImportStep.Problem.UNREADABLE)
                parsed.tasks.isEmpty() ->
                    ImportStep.Failed(ImportStep.Problem.NO_TASKS, parsed.skippedComponents)
                else -> ImportStep.Ready(parsed)
            }
        }
    }

    fun choose(target: ImportChoice) {
        choice.value = target
    }

    /** Back to the start, so another file can be picked. */
    fun reset() {
        job?.cancel()
        lastUri = null
        choice.value = null
        step.value = ImportStep.Idle
    }

    fun import(newListColor: Int, fallbackName: String) {
        val current = state.value
        val ready = current.step as? ImportStep.Ready ?: return
        val chosen = current.target ?: return
        val target = when (chosen) {
            is ImportChoice.Existing -> ImportTarget.Existing(chosen.list.id)
            is ImportChoice.NewList -> ImportTarget.NewList(chosen.name.ifBlank { fallbackName }, newListColor)
        }
        val listName = when (chosen) {
            is ImportChoice.Existing -> chosen.list.name
            is ImportChoice.NewList -> chosen.name.ifBlank { fallbackName }
        }
        step.value = ImportStep.Importing
        job = viewModelScope.launch {
            step.value = try {
                val result = importer.import(ready.parsed, target)
                (chosen as? ImportChoice.Existing)?.list
                    ?.takeIf { it.accountId != null && result.imported > 0 }
                    ?.let { runCatching { syncTrigger.enqueue(it.accountName) } }
                ImportStep.Done(
                    listName = listName,
                    imported = result.imported,
                    skippedExisting = result.skippedExisting + ready.parsed.duplicatesInFile,
                    skippedComponents = ready.parsed.skippedComponents,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ImportStep.Failed(ImportStep.Problem.WRITE_FAILED)
            }
        }
    }
}

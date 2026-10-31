package com.aiexile.agendula.data.export

import com.aiexile.agendula.data.di.IoDispatcher
import com.aiexile.agendula.data.tasks.TasksDataSource
import com.aiexile.agendula.domain.export.ExportDocument
import com.aiexile.agendula.domain.export.ExportList
import com.aiexile.agendula.domain.export.ICalendarWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the user's task lists into `.ics` documents.
 *
 * Export is a v1 feature rather than a nicety because of where the data now
 * lives: our own provider is inside the app's private storage, so in Local mode a
 * user's tasks exist in exactly one place and uninstalling deletes them. On Play,
 * where most people will never have a sync engine, that is the majority case.
 *
 * **One document per list**, because a list is a CalDAV collection and that is the
 * unit every other client understands. Bundling everything into a single file
 * would flatten the lists away, and list membership is not recoverable from a
 * VTODO afterwards.
 */
@Singleton
class TaskExporter @Inject constructor(
    private val dataSource: TasksDataSource,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Serialises [listIds] — every visible list when null.
     *
     * A list with no tasks still produces a document. An empty `.ics` is a real
     * answer ("this list is empty"), whereas a missing file is indistinguishable
     * from the export having gone wrong.
     */
    suspend fun export(listIds: Set<Long>? = null): List<ExportDocument> = withContext(io) {
        dataSource.taskLists()
            .filter { listIds == null || it.id in listIds }
            .map { list ->
                val document = ExportList(
                    listId = list.id,
                    name = list.name,
                    accountName = list.accountName,
                    tasks = dataSource.exportTasks(list.id),
                )
                ExportDocument(
                    fileName = fileNameFor(list.name, list.id),
                    content = ICalendarWriter.write(document).toByteArray(Charsets.UTF_8),
                )
            }
    }

    companion object {

        /**
         * A file name derived from the list name, safe on every filesystem the
         * user might pick through SAF (including FAT32 on an SD card).
         *
         * The list id is appended rather than trusted to be redundant: two lists on
         * different accounts may share a name, and two exports landing on the same
         * file would silently lose one of them.
         */
        fun fileNameFor(listName: String, listId: Long): String {
            val safe = listName
                .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
                .joinToString("")
                .trim('-')
                .take(60)
                .ifBlank { "list" }
            return "$safe-$listId.ics"
        }
    }
}

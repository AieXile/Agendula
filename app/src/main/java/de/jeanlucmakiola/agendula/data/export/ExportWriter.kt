package de.jeanlucmakiola.agendula.data.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.agendula.data.di.IoDispatcher
import de.jeanlucmakiola.agendula.domain.export.ExportDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Where an export ended up, for the UI to report. */
data class ExportResult(val fileCount: Int, val taskListNames: List<String>)

/**
 * Why an export failed, as a value rather than a message: the UI is translated
 * (see `res/xml/locales_config.xml`), so the wording has to come from a string
 * resource rather than being built here.
 */
enum class ExportFailure {
    FOLDER_UNAVAILABLE,
    FOLDER_NOT_WRITABLE,
    CANNOT_CREATE_FILE,
    LOST_ACCESS,
    WRITE_FAILED,
}

/** The export could not be written. */
class ExportFailedException(
    val failure: ExportFailure,
    cause: Throwable? = null,
) : IOException(failure.name, cause)

/**
 * Writes [ExportDocument]s to a user-chosen location through the Storage Access
 * Framework.
 *
 * No storage permission anywhere: SAF hands us a `Uri` the user picked
 * themselves, which is both the modern approach and the only one that still works
 * on scoped storage. The caller owns launching `ACTION_CREATE_DOCUMENT` (for
 * [writeZip]) or `ACTION_OPEN_DOCUMENT_TREE` (for [writeToTree]) and passes the
 * result here.
 *
 * floret-kit material — the plumbing is
 * not task-domain and Calendula will want the same thing. Kept app-local for now
 * on the kit's own stated principle of not extracting until a second consumer
 * actually exists; the seam is here, so moving it later is a file move.
 */
@Singleton
class ExportWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Writes every document into [treeUri], a directory the user picked.
     *
     * A same-named file is truncated and rewritten in place rather than deleted
     * and recreated: SAF would otherwise append " (1)" and turn the folder into
     * an unusable pile of snapshots, and a delete that is not followed by a
     * successful create loses the previous export outright.
     *
     * The directory is listed once. `DocumentFile.findFile` queries the whole
     * tree per call, so looking each name up in the loop is one full
     * cross-process directory scan per list.
     */
    suspend fun writeToTree(treeUri: Uri, documents: List<ExportDocument>): ExportResult =
        withContext(io) {
            runCatching {
                val tree = DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw ExportFailedException(ExportFailure.FOLDER_UNAVAILABLE)
                if (!tree.canWrite()) throw ExportFailedException(ExportFailure.FOLDER_NOT_WRITABLE)
                val existing = tree.listFiles().associateBy { it.name }

                documents.forEach { document ->
                    val file = existing[document.fileName]
                        ?: tree.createFile(MIME_ICALENDAR, document.fileName)
                        ?: throw ExportFailedException(ExportFailure.CANNOT_CREATE_FILE)
                    write(file.uri, document.content)
                }
            }.getOrElse { throw asExportFailure(it) }
            ExportResult(documents.size, documents.map { it.fileName })
        }

    /**
     * Writes every document into a single zip at [target].
     *
     * The one-file form, for sharing or for a backup the user filed somewhere
     * themselves — one attachment rather than one per list.
     */
    suspend fun writeZip(target: Uri, documents: List<ExportDocument>): ExportResult =
        withContext(io) {
            runCatching {
                context.contentResolver.openOutputStream(target, "wt")?.use { raw ->
                    ZipOutputStream(raw.buffered()).use { zip ->
                        documents.forEach { document ->
                            zip.putNextEntry(ZipEntry(document.fileName))
                            zip.write(document.content)
                            zip.closeEntry()
                        }
                    }
                } ?: throw ExportFailedException(ExportFailure.WRITE_FAILED)
            }.getOrElse { throw asExportFailure(it) }
            ExportResult(documents.size, documents.map { it.fileName })
        }

    private fun write(target: Uri, bytes: ByteArray) {
        runCatching {
            // "wt" truncates. Without it a shorter export leaves the tail of the
            // previous, longer one behind and produces a corrupt file.
            context.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) }
                ?: throw ExportFailedException(ExportFailure.WRITE_FAILED)
        }.getOrElse { throw asExportFailure(it) }
    }

    private fun asExportFailure(cause: Throwable): Throwable = when (cause) {
        is ExportFailedException -> cause
        // A SAF grant can be revoked between the picker and the write (the volume
        // was unmounted, the provider's process died, the user cleared the grant).
        is SecurityException -> ExportFailedException(ExportFailure.LOST_ACCESS, cause)
        is IOException -> ExportFailedException(ExportFailure.WRITE_FAILED, cause)
        else -> cause
    }

    private companion object {
        const val MIME_ICALENDAR = "text/calendar"
    }
}

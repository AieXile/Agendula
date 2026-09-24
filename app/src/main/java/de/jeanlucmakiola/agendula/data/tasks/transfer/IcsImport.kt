package de.jeanlucmakiola.agendula.data.tasks.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.agendula.data.di.IoDispatcher
import de.jeanlucmakiola.agendula.data.tasks.ical.CalendarResource
import de.jeanlucmakiola.agendula.data.tasks.ical.VTodoMapper
import de.jeanlucmakiola.agendula.data.tasks.room.TaskEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TaskListEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TasksDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** One `.ics` document, with the name it arrived under (for a new list's name). */
data class IcsSource(val name: String?, val text: String)

/** One task to import: a series master (or a lone task) and its `RECURRENCE-ID` overrides. */
class ImportedTask(
    val uid: String,
    val master: VTodoMapper.Mapped,
    val overrides: List<VTodoMapper.Mapped>,
)

/** What a picked file holds, before anything is written. */
data class ParsedIcs(
    val tasks: List<ImportedTask>,
    /** `VEVENT`s and `VJOURNAL`s — a task app has nowhere to put them. */
    val skippedComponents: Int,
    /** Documents (or zip entries) that did not parse as iCalendar at all. */
    val unreadableDocuments: Int,
    /** Copies of a UID the file itself repeats, e.g. the same task in two zipped lists. */
    val duplicatesInFile: Int,
    /** `X-WR-CALNAME` when every calendar agrees on one, else the file name. */
    val suggestedListName: String?,
    /** The picked file's own name, for the screen to show. */
    val fileName: String? = null,
)

/** Where the tasks go. */
sealed interface ImportTarget {
    data class Existing(val listId: Long) : ImportTarget

    /** A new device-only list. */
    data class NewList(val name: String, val color: Int) : ImportTarget
}

data class IcsImportResult(
    val listId: Long,
    val imported: Int,
    /** Tasks whose UID the target list already held. */
    val skippedExisting: Int,
)

/** The database, as the import needs it — a seam so the logic is testable on the JVM. */
interface IcsImportStore {
    fun <T> transaction(block: () -> T): T
    fun createList(name: String, color: Int): Long
    fun hasUid(listId: Long, uid: String): Boolean
    fun insert(row: TaskEntity): Long
    fun masterByUid(listId: Long, uid: String): TaskEntity?
    fun setParent(taskId: Long, parentId: Long?)
}

/**
 * Imports `.ics` files — or a zip of them, as the export writes — into the own
 * Room store.
 *
 * Goes through the same [CalendarResource] / [VTodoMapper] path CalDAV sync
 * uses, so recurrence, `RECURRENCE-ID` overrides, `RELATED-TO` parents and every
 * property the mapper does not claim (`VALARM`s included) survive as they would
 * on a download. `VALARM`s stay in the residue: like sync, the import does not
 * turn them into local reminders.
 *
 * Tasks whose UID the target list already holds are skipped, so importing the
 * same file twice is harmless.
 */
@Singleton
class IcsImport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: TasksDatabase,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Reads and parses [uri]; null when it cannot be read at all. */
    suspend fun read(uri: Uri): ParsedIcs? = withContext(io) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readCapped() }
                ?: return@runCatching null
            val name = displayName(uri)
            parse(sourcesOf(name, bytes), name).copy(fileName = name)
        }.getOrNull()
    }

    suspend fun import(parsed: ParsedIcs, target: ImportTarget): IcsImportResult = withContext(io) {
        write(parsed, target, RoomIcsImportStore(database))
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    companion object {

        /** Refuses anything larger — a task export is kilobytes, not tens of megabytes. */
        const val MAX_BYTES = 32L * 1024 * 1024

        /** Splits a zip into its `.ics` entries; anything else is one document. */
        fun sourcesOf(name: String?, bytes: ByteArray): List<IcsSource> {
            if (!isZip(bytes)) return listOf(IcsSource(name, decode(bytes)))
            val sources = mutableListOf<IcsSource>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".ics", ignoreCase = true)) {
                        sources += IcsSource(entry.name.substringAfterLast('/'), decode(zip.readCapped()))
                    }
                    entry = zip.nextEntry
                }
            }
            return sources
        }

        /** [fileName] names a multi-document zip when its calendars carry no name. */
        fun parse(sources: List<IcsSource>, fileName: String? = null): ParsedIcs {
            var unreadable = 0
            var skipped = 0
            var duplicates = 0
            val calendarNames = mutableListOf<String?>()
            val groups = LinkedHashMap<String, MutableList<VTodoMapper.Mapped>>()

            for (source in sources) {
                val calendars = runCatching { CalendarResource.parse(source.text) }.getOrNull()
                if (calendars.isNullOrEmpty()) {
                    unreadable++
                    continue
                }
                calendars.forEach { calendar ->
                    calendarNames += calendar.property("X-WR-CALNAME")?.value?.trim()?.takeIf { it.isNotEmpty() }
                    skipped += calendar.components("VEVENT").size + calendar.components("VJOURNAL").size
                }
                CalendarResource.todosIn(calendars).forEach { vtodo ->
                    val mapped = VTodoMapper.read(vtodo).let {
                        if (it.uidWasMissing) {
                            it.copy(entity = it.entity.copy(uid = UUID.randomUUID().toString()))
                        } else {
                            it
                        }
                    }
                    val group = groups.getOrPut(mapped.entity.uid) { mutableListOf() }
                    // The same component twice — within one document, or across
                    // two zipped lists — lands once.
                    if (group.none { it.entity.recurrenceId == mapped.entity.recurrenceId }) {
                        group += mapped
                    } else if (mapped.entity.recurrenceId == null) {
                        duplicates++
                    }
                }
            }

            val tasks = groups.map { (uid, mapped) ->
                val master = mapped.firstOrNull { it.entity.recurrenceId == null } ?: mapped.first()
                ImportedTask(uid, master, mapped.filter { it !== master })
            }
            val sourceName = sources.singleOrNull()?.name ?: fileName
            return ParsedIcs(
                tasks = tasks,
                skippedComponents = skipped,
                unreadableDocuments = unreadable,
                duplicatesInFile = duplicates,
                suggestedListName = calendarNames.distinct().singleOrNull()
                    ?: sourceName?.let(::nameFromFile),
            )
        }

        /**
         * Writes [parsed] into [target] in one transaction: a failure leaves the
         * store as it was.
         */
        fun write(parsed: ParsedIcs, target: ImportTarget, store: IcsImportStore): IcsImportResult =
            store.transaction {
                val listId = when (target) {
                    is ImportTarget.Existing -> target.listId
                    is ImportTarget.NewList -> store.createList(target.name.trim(), target.color)
                }
                var imported = 0
                var skipped = 0
                val parents = mutableListOf<Pair<Long, String>>()
                for (task in parsed.tasks) {
                    if (store.hasUid(listId, task.uid)) {
                        skipped++
                        continue
                    }
                    val masterId = store.insert(task.master.entity.forImport(listId, masterId = null))
                    task.overrides.forEach { store.insert(it.entity.forImport(listId, masterId)) }
                    task.master.parentUid?.let { parents += masterId to it }
                    imported++
                }
                // After every insert: a parent may come later in the file than its child.
                parents.forEach { (childId, parentUid) ->
                    val parent = store.masterByUid(listId, parentUid)
                    if (parent != null && parent.id != childId) store.setParent(childId, parent.id)
                }
                IcsImportResult(listId, imported, skipped)
            }

        private fun TaskEntity.forImport(listId: Long, masterId: Long?) = copy(
            id = 0L,
            listId = listId,
            masterId = masterId,
            parentId = null,
            href = null,
            etag = null,
            // A list attached to an account uploads what lands in it.
            isDirty = true,
            isDeleted = false,
        )

        /** "Groceries-3.ics" → "Groceries-3"; the export's id suffix is kept, it may be a real name. */
        internal fun nameFromFile(fileName: String): String? =
            fileName.substringAfterLast('/')
                .replace(Regex("\\.(ics|ical|ifb|icalendar|zip)$", RegexOption.IGNORE_CASE), "")
                .trim()
                .takeIf { it.isNotEmpty() }

        private fun isZip(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
                bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

        private fun decode(bytes: ByteArray): String =
            bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")

        private fun InputStream.readCapped(): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                total += read
                check(total <= MAX_BYTES) { "file too large" }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}

class RoomIcsImportStore(private val database: TasksDatabase) : IcsImportStore {

    override fun <T> transaction(block: () -> T): T = database.runInTransaction<T> { block() }

    override fun createList(name: String, color: Int): Long =
        database.taskLists().insert(TaskListEntity(name = name, color = color))

    // Tombstones and overrides count: either would collide on the unique index
    // or resurrect something the user deleted.
    override fun hasUid(listId: Long, uid: String): Boolean =
        database.query(
            "SELECT 1 FROM tasks WHERE list_id = ? AND uid = ? LIMIT 1",
            arrayOf<Any?>(listId, uid),
        ).use { it.moveToFirst() }

    override fun insert(row: TaskEntity): Long = database.tasks().insert(row)

    override fun masterByUid(listId: Long, uid: String): TaskEntity? = database.tasks().byUid(listId, uid)

    override fun setParent(taskId: Long, parentId: Long?) {
        database.tasks().setParent(taskId, parentId)
    }
}

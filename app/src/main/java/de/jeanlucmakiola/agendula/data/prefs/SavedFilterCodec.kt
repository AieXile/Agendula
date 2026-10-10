package de.jeanlucmakiola.agendula.data.prefs

import de.jeanlucmakiola.agendula.domain.DueWindow
import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.SavedFilter
import de.jeanlucmakiola.agendula.domain.TagMatch
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Stored form of the saved filters: one line per filter,
 * `id|name|due|lists|tags|match|priority`, with name and tags URL-encoded and
 * list ids qualified by the store they belong to (`OWN:3`), since each store
 * numbers its lists on its own. Fixed once released — don't change without a
 * data migration.
 */
internal object SavedFilterCodec {

    /** The filters in [raw], with only the list ids of [mode]'s store. */
    fun decode(raw: String?, mode: String): List<SavedFilter> =
        lines(raw).mapNotNull { runCatching { decodeLine(it, mode) }.getOrNull() }

    /** [raw] with [filter] replacing the line of the same id, or appended. */
    fun upsert(raw: String?, filter: SavedFilter, mode: String): String {
        val existing = lines(raw)
        val index = existing.indexOfFirst { idOf(it) == filter.id }
        // Keep the lists another store contributed, so switching stores and back
        // doesn't widen a filter that was edited in between, unless "any list"
        // was chosen here, which drops them on purpose.
        val foreign = if (filter.anyList) {
            emptyList()
        } else {
            existing.getOrNull(index)?.let(::fields)?.getOrNull(3)
                ?.let(::splitList)?.filterNot { it.startsWith("$mode:") }.orEmpty()
        }
        val line = encodeLine(filter, mode, foreign)
        val updated = if (index >= 0) existing.toMutableList().also { it[index] = line } else existing + line
        return updated.joinToString("\n")
    }

    fun remove(raw: String?, id: Long): String = lines(raw).filterNot { idOf(it) == id }.joinToString("\n")

    private fun lines(raw: String?): List<String> = raw.orEmpty().split('\n').filter { it.isNotBlank() }

    private fun fields(line: String): List<String> = line.split('|')

    private fun idOf(line: String): Long? = fields(line).firstOrNull()?.toLongOrNull()

    private fun splitList(value: String): List<String> = value.split(',').filter { it.isNotEmpty() }

    private fun encodeLine(filter: SavedFilter, mode: String, foreignLists: List<String>): String =
        listOf(
            filter.id.toString(),
            encode(filter.name),
            filter.due.sorted().joinToString(",") { it.name },
            (filter.listIds.sorted().map { "$mode:$it" } + foreignLists).joinToString(","),
            filter.tags.sorted().joinToString(",") { encode(it) },
            filter.tagMatch.name,
            filter.minPriority.name,
        ).joinToString("|")

    private fun decodeLine(line: String, mode: String): SavedFilter? {
        val f = fields(line)
        if (f.size < 7) return null
        val id = f[0].toLongOrNull() ?: return null
        val lists = splitList(f[3])
        val here = lists.filter { it.startsWith("$mode:") }.mapNotNull { it.removePrefix("$mode:").toLongOrNull() }.toSet()
        return SavedFilter(
            id = id,
            name = decode(f[1]),
            due = splitList(f[2]).mapNotNull { runCatching { DueWindow.valueOf(it) }.getOrNull() }.toSet(),
            listIds = here,
            listsElsewhere = lists.any { !it.startsWith("$mode:") },
            tags = splitList(f[4]).map(::decode).toSet(),
            tagMatch = runCatching { TagMatch.valueOf(f[5]) }.getOrDefault(TagMatch.ANY),
            minPriority = runCatching { Priority.valueOf(f[6]) }.getOrDefault(Priority.NONE),
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
}

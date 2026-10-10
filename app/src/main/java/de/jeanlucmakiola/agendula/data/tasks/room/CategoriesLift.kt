package de.jeanlucmakiola.agendula.data.tasks.room

import de.jeanlucmakiola.agendula.domain.ical.ICalComponent
import de.jeanlucmakiola.agendula.domain.ical.ICalParser
import de.jeanlucmakiola.agendula.domain.ical.ICalProperty
import de.jeanlucmakiola.agendula.domain.ical.ICalSerializer
import de.jeanlucmakiola.agendula.domain.ical.ICalValues

/**
 * Lifts `CATEGORIES` out of a residue written before v2 claimed it.
 *
 * ⚠️ Frozen with [TasksDatabase.MIGRATION_1_2]. The claim rule is copied here
 * rather than shared with the mapper, so a later change to what the mapper
 * claims cannot change what this migration produced for an earlier upgrader.
 */
internal object CategoriesLift {

    /**
     * Every copy's values merged, and the residue with the one claimed line
     * removed — or `null` for the residue when nothing was claimed, so the caller
     * leaves the stored one alone.
     */
    data class Result(val categories: List<String>, val residue: String?, val claimed: Boolean)

    fun lift(residue: String?): Result {
        val component = parse(residue) ?: return Result(emptyList(), residue, claimed = false)
        val copies = component.properties.filter { it.name.equals("CATEGORIES", ignoreCase = true) }
        val values = copies.flatMap(::values)
        val claim = copies.singleOrNull()?.takeIf {
            it.params.isEmpty() && values.isNotEmpty() && ICalValues.formatTextList(values) == it.value
        } ?: return Result(values, residue, claimed = false)
        val rest = ICalSerializer.serializeProperties(component.properties.filterNot { it === claim }) +
            ICalSerializer.serializeAll(component.components)
        return Result(values, rest.ifEmpty { null }, claimed = true)
    }

    private fun values(property: ICalProperty): List<String> =
        ICalValues.parseTextList(property.value).map(String::trim).filter(String::isNotEmpty).distinct()

    private fun parse(text: String?): ICalComponent? {
        if (text.isNullOrEmpty()) return null
        return runCatching { ICalParser.parse("BEGIN:VTODO\r\n$text\r\nEND:VTODO\r\n") }.getOrNull()
    }
}

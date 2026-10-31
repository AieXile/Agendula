package com.aiexile.agendula.domain.ical

/**
 * A parameter on a content line: `TZID=Europe/Berlin`, `MEMBER="a","b"`.
 *
 * Values are held **unquoted**. Quoting is optional in RFC 5545 and carries no
 * meaning, so it is normalised away on parse and reapplied on serialise only
 * where the grammar forces it, which is why parameter quoting is on the
 * round-trip allowlist.
 */
data class ICalParam(val name: String, val values: List<String>) {
    constructor(name: String, value: String) : this(name, listOf(value))
}

/**
 * One content line.
 *
 * [value] is kept **exactly as it arrived**, unfolded but still escaped. That is
 * deliberate and it is the whole reason this model exists instead of a typed
 * one: a property we do not model is re-emitted from this string verbatim, so it
 * cannot be normalised, reordered inside itself, or lost. Decoding happens in
 * the mapper, for the properties the mapper actually claims.
 */
data class ICalProperty(
    val name: String,
    val params: List<ICalParam> = emptyList(),
    val value: String,
) {
    /** First value of [name], unquoted, or `null`. */
    fun param(name: String): String? =
        params.firstOrNull { it.name.equals(name, ignoreCase = true) }?.values?.firstOrNull()
}

/** A `BEGIN:`/`END:` block — `VCALENDAR`, `VTODO`, `VALARM`, `VTIMEZONE`, or one we don't know. */
data class ICalComponent(
    val name: String,
    val properties: List<ICalProperty> = emptyList(),
    val components: List<ICalComponent> = emptyList(),
) {
    fun property(name: String): ICalProperty? =
        properties.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun properties(name: String): List<ICalProperty> =
        properties.filter { it.name.equals(name, ignoreCase = true) }

    fun components(name: String): List<ICalComponent> =
        components.filter { it.name.equals(name, ignoreCase = true) }

    /** This component with every property in [names] removed. Sub-components are untouched. */
    fun without(names: Set<String>): ICalComponent {
        val upper = names.map { it.uppercase() }.toSet()
        return copy(properties = properties.filterNot { it.name.uppercase() in upper })
    }

    /** True when nothing survives — no properties and no sub-components worth keeping. */
    fun isEmpty(): Boolean = properties.isEmpty() && components.isEmpty()
}

/** Thrown when input is not recoverable as iCalendar at all. */
class ICalParseException(message: String) : Exception(message)

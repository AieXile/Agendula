package de.jeanlucmakiola.agendula.domain.ical

/**
 * Serialises an [ICalComponent] tree back to RFC 5545 text.
 *
 * The inverse of [ICalParser] for everything the parser preserves: property
 * order within a component, parameter order, and property values byte-for-byte.
 * What it does *not* preserve is fold position and parameter quoting, neither of
 * which carries information — both are on the round-trip allowlist.
 */
object ICalSerializer {

    private const val CRLF = "\r\n"

    /** RFC 5545 §3.1 caps a content line at 75 octets, excluding the CRLF. */
    private const val MAX_LINE_OCTETS = 75

    fun serialize(component: ICalComponent): String = buildString {
        write(component)
    }

    /** Serialises [components] one after another — the form the residue is stored in. */
    fun serializeAll(components: List<ICalComponent>): String = buildString {
        components.forEach { write(it) }
    }

    /** Serialises bare properties with no enclosing component. */
    fun serializeProperties(properties: List<ICalProperty>): String = buildString {
        properties.forEach { line(render(it)) }
    }

    private fun StringBuilder.write(component: ICalComponent) {
        line("BEGIN:${component.name}")
        component.properties.forEach { line(render(it)) }
        component.components.forEach { write(it) }
        line("END:${component.name}")
    }

    private fun StringBuilder.line(content: String) {
        append(fold(content)).append(CRLF)
    }

    internal fun render(property: ICalProperty): String = buildString {
        append(property.name)
        for (param in property.params) {
            append(';').append(param.name)
            if (param.values.isNotEmpty()) {
                append('=')
                append(param.values.joinToString(",") { quoteIfNeeded(it) })
            }
        }
        append(':').append(property.value)
    }

    /**
     * A parameter value is quoted only when the grammar forces it — it may not
     * contain a colon, semicolon or comma unquoted. Any embedded double quote is
     * dropped, because RFC 5545 gives it no escape and emitting one produces a
     * line no parser can read back.
     */
    private fun quoteIfNeeded(value: String): String {
        if (value.none { it == ':' || it == ';' || it == ',' }) return value
        return "\"" + value.replace("\"", "") + "\""
    }

    /**
     * Folds a content line to at most [MAX_LINE_OCTETS] octets, continuing with
     * CRLF + a single space.
     *
     * Counted in **octets, not characters** — the limit is defined that way, and
     * an emoji in a task title is four of them. Splits stay on character
     * boundaries so a fold can never cut a UTF-8 sequence in half.
     */
    internal fun fold(content: String): String {
        if (content.utf8Size() <= MAX_LINE_OCTETS) return content

        val out = StringBuilder()
        var octets = 0
        // The first line takes the full budget; every continuation loses one octet
        // to its leading space.
        var budget = MAX_LINE_OCTETS
        var index = 0
        while (index < content.length) {
            val codePoint = content.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val size = String(Character.toChars(codePoint)).utf8Size()
            if (octets + size > budget) {
                out.append(CRLF).append(' ')
                octets = 0
                budget = MAX_LINE_OCTETS - 1
            }
            out.append(content, index, index + charCount)
            octets += size
            index += charCount
        }
        return out.toString()
    }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
}

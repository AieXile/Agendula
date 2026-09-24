package de.jeanlucmakiola.agendula.domain.ical

/**
 * Parses RFC 5545 text into an [ICalComponent] tree.
 *
 * Lexical only: it splits content lines into name, parameters and value and
 * nests `BEGIN`/`END` blocks. It does not interpret a single value — that is the
 * mapper's job, and keeping the two apart is what lets an unrecognised property
 * survive a read-modify-write cycle untouched.
 *
 * Deliberately tolerant, because servers and other clients are not careful:
 * unknown components nest like any other, a stray `END` without its `BEGIN` is
 * ignored rather than fatal, and a line with no colon is skipped. Only a missing
 * outer component is fatal.
 */
object ICalParser {

    /**
     * Written as an escape, not as the character itself: a literal BOM anywhere
     * but byte 0 of a file is what Android Lint's `ByteOrderMark` check exists to
     * catch, and it fails the build.
     */
    private const val BOM = "\uFEFF"

    fun parse(text: String): ICalComponent =
        parseAll(text).firstOrNull() ?: throw ICalParseException("no component found")

    /** Every top-level component in [text]. Normally one `VCALENDAR`. */
    fun parseAll(text: String): List<ICalComponent> {
        val roots = mutableListOf<ICalComponent>()
        val stack = ArrayDeque<Builder>()

        for (line in unfold(text)) {
            val property = parseLine(line) ?: continue
            when {
                property.name.equals("BEGIN", ignoreCase = true) ->
                    stack.addLast(Builder(property.value.trim().uppercase()))

                property.name.equals("END", ignoreCase = true) -> {
                    // Close by *name*, not by position. A resource with a missing
                    // END:VTODO would otherwise have its END:VCALENDAR close the
                    // VTODO, leave VCALENDAR open, and end with no component at
                    // all — discarding a whole multiget response over one
                    // malformed task.
                    val name = property.value.trim().uppercase()
                    val depth = stack.indexOfLast { it.name == name }
                    if (depth < 0) continue
                    repeat(stack.size - depth) { close(stack, roots) }
                }

                // A property before any BEGIN is malformed; dropping it is the only
                // option that doesn't invent a component to hang it on.
                else -> stack.lastOrNull()?.properties?.add(property)
            }
        }
        // Anything still open at end of input is missing its END. Keeping it is
        // strictly better than dropping it.
        while (stack.isNotEmpty()) close(stack, roots)
        return roots
    }

    private fun close(stack: ArrayDeque<Builder>, roots: MutableList<ICalComponent>) {
        val component = stack.removeLast().build()
        val parent = stack.lastOrNull()
        if (parent == null) roots += component else parent.components += component
    }

    /**
     * Splits [text] into unfolded content lines.
     *
     * RFC 5545 §3.1: a CRLF followed by a single space or tab is a fold and both
     * are removed. Bare LF is accepted because plenty of real files carry it, and
     * a leading BOM is stripped.
     */
    internal fun unfold(text: String): List<String> {
        val lines = mutableListOf<StringBuilder>()
        val normalised = text.removePrefix(BOM)
        for (raw in normalised.split("\r\n", "\n", "\r")) {
            if (raw.isEmpty()) continue
            val continuation = raw[0] == ' ' || raw[0] == '\t'
            if (continuation && lines.isNotEmpty()) {
                lines.last().append(raw, 1, raw.length)
            } else {
                lines += StringBuilder(raw)
            }
        }
        return lines.map { it.toString() }
    }

    /**
     * Splits one unfolded line into name, parameters and value.
     *
     * The value separator is the first colon **outside a quoted parameter value**
     * — `ATTENDEE;CN="Smith, J:r":mailto:x` has three colons and only the third
     * one ends the parameter list.
     */
    internal fun parseLine(line: String): ICalProperty? {
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') quoted = !quoted
            else if (c == ':' && !quoted) { colon = i; break }
        }
        // An unbalanced quote in the parameter section leaves the scan stuck
        // inside a quoted string forever. Falling back to the first colon keeps
        // the line instead of dropping it whole.
        if (colon < 0) colon = line.indexOf(':')
        if (colon < 0) return null

        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)

        val segments = splitUnquoted(head, ';')
        val name = segments.firstOrNull()?.trim().orEmpty()
        if (name.isEmpty()) return null

        val params = segments.drop(1).mapNotNull { segment ->
            val eq = segment.indexOf('=')
            // A parameter with no '=' is non-conformant. Keep it as a valueless
            // parameter rather than dropping it — it is still someone's data.
            if (eq < 0) return@mapNotNull ICalParam(segment.trim(), emptyList())
            val paramName = segment.substring(0, eq).trim()
            if (paramName.isEmpty()) return@mapNotNull null
            val values = splitUnquoted(segment.substring(eq + 1), ',').map { it.unquote() }
            ICalParam(paramName, values)
        }

        return ICalProperty(name, params, value)
    }

    /** Splits on [delimiter], ignoring delimiters inside double quotes. */
    private fun splitUnquoted(text: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (c in text) {
            when {
                c == '"' -> { quoted = !quoted; current.append(c) }
                c == delimiter && !quoted -> { out += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        out += current.toString()
        return out
    }

    private fun String.unquote(): String =
        if (length >= 2 && startsWith('"') && endsWith('"')) substring(1, length - 1) else this

    private class Builder(val name: String) {
        val properties = mutableListOf<ICalProperty>()
        val components = mutableListOf<ICalComponent>()
        fun build() = ICalComponent(name, properties.toList(), components.toList())
    }
}

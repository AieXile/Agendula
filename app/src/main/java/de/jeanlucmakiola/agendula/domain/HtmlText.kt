package de.jeanlucmakiola.agendula.domain

// A tag name ends at whitespace, `>` or `/`; `<b.smith@…>` is not a tag.
private const val TAG_END = "(?=[\\s>/])"

// Markup, not prose with a `<` in it: a known element closed, or a line break.
private val htmlTag = Regex(
    "</(p|div|span|b|i|u|s|strong|em|a|ul|ol|li|h[1-6]|html|body|font|table|tr|td|th|pre|code|blockquote)\\s*>|<(br|hr)\\s*/?>",
    RegexOption.IGNORE_CASE,
)
private val lineBreak = Regex("<br\\s*/?>|<hr$TAG_END[^>]*>|</(p|div|h[1-6]|tr|pre|blockquote)>", RegexOption.IGNORE_CASE)
private val listItem = Regex("<li$TAG_END[^>]*>", RegexOption.IGNORE_CASE)
private val anchor = Regex("<a\\s[^>]*href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val hidden = Regex("<(head|style|script)$TAG_END.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val anyTag = Regex("</?[a-zA-Z][a-zA-Z0-9]*$TAG_END[^>]*>|<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
private val namedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

/** Whether this looks like markup another client wrote, rather than text that merely contains a `<`. */
fun String.looksLikeHtml(): Boolean = htmlTag.containsMatchIn(this)

/**
 * The text a reader would see in this HTML: block ends and `<br>` become line
 * breaks, list items bullets, and a link whose text is not its address keeps the
 * address after it.
 */
fun String.htmlToPlainText(): String {
    // Markup that breaks its own lines means its newlines are only source layout.
    val structured = lineBreak.containsMatchIn(this) || listItem.containsMatchIn(this)
    val text = replace("\r\n", "\n").let { if (structured) it.replace('\n', ' ') else it }
        .replace(hidden, "")
        .replace(anchor) { m ->
            val href = m.groupValues[1].removePrefix("mailto:")
            val label = m.groupValues[2].replace(anyTag, "").trim()
            if (label.isEmpty() || label == href) href else "$label ($href)"
        }
        .replace(listItem, "\n• ")
        .replace(lineBreak, "\n")
        .replace(anyTag, "")
        .replace(entity) { m -> decodeEntity(m.groupValues[1]) ?: m.value }
    return text.lines().joinToString("\n") { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun decodeEntity(name: String): String? = when {
    name.startsWith("#x", ignoreCase = true) -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
    name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
    else -> namedEntities[name.lowercase()]
}

private fun codePoint(value: Int): String? =
    if (Character.isValidCodePoint(value)) String(Character.toChars(value)) else null

/** The description as it should be read: HTML flattened to text, anything else as written. */
fun String.readableDescription(): String = if (looksLikeHtml()) htmlToPlainText() else this

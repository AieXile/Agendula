package com.aiexile.agendula.ui.common

import android.util.Patterns
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/** [text] with every web address, e-mail address and phone number made tappable. */
@Composable
fun linkified(text: String): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return remember(text, color) {
        val styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
        val links = buildList {
            Patterns.WEB_URL.matcher(text).let { m ->
                while (m.find()) add(Triple(m.start(), m.end(), m.group().let { if ("://" in it) it else "https://$it" }))
            }
            Patterns.EMAIL_ADDRESS.matcher(text).let { m ->
                while (m.find()) add(Triple(m.start(), m.end(), "mailto:${m.group()}"))
            }
        }.sortedBy { it.first }
            .fold(emptyList<Triple<Int, Int, String>>()) { kept, link ->
                if (kept.lastOrNull()?.let { link.first < it.second } == true) kept else kept + link
            }
        buildAnnotatedString {
            append(text)
            links.forEach { (start, end, url) -> addLink(LinkAnnotation.Url(url, styles), start, end) }
        }
    }
}

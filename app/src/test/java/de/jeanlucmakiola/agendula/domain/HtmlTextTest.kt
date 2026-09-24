package de.jeanlucmakiola.agendula.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HtmlTextTest {

    @Test
    fun `plain text with angle brackets is not html`() {
        assertThat("a < b and c > d".looksLikeHtml()).isFalse()
        assertThat("x <3".readableDescription()).isEqualTo("x <3")
        assertThat("if x<b and y>c".looksLikeHtml()).isFalse()
        assertThat("Mail <b.smith@example.com>".looksLikeHtml()).isFalse()
    }

    @Test
    fun `text that only resembles a tag survives inside real html`() {
        assertThat("<p>Mail <b.smith@example.com></p>".htmlToPlainText()).isEqualTo("Mail <b.smith@example.com>")
    }

    @Test
    fun `paragraphs and breaks become lines`() {
        val html = "<p>First line<br>second</p>\n<p>Next &amp; last</p>"
        assertThat(html.htmlToPlainText()).isEqualTo("First line\nsecond\nNext & last")
    }

    @Test
    fun `list items become bullets`() {
        val html = "<ul>\n  <li>Milk</li>\n  <li><b>Eggs</b></li>\n</ul>"
        assertThat(html.htmlToPlainText()).isEqualTo("• Milk\n• Eggs")
    }

    @Test
    fun `a link keeps its address`() {
        assertThat("""See <a href="https://example.org">the docs</a>""".htmlToPlainText())
            .isEqualTo("See the docs (https://example.org)")
        assertThat("""<a href="https://example.org">https://example.org</a>""".htmlToPlainText())
            .isEqualTo("https://example.org")
        assertThat("""<a href="mailto:a@b.de">a@b.de</a>""".htmlToPlainText()).isEqualTo("a@b.de")
    }

    @Test
    fun `inline markup keeps the source line breaks`() {
        assertThat("one <b>bold</b>\ntwo".htmlToPlainText()).isEqualTo("one bold\ntwo")
    }

    @Test
    fun `entities decode and bogus ones stay`() {
        assertThat("<span>&lt;tag&gt; &#228; &#xE9; &#99999999; &bogus;</span>".htmlToPlainText())
            .isEqualTo("<tag> ä é &#99999999; &bogus;")
    }

    @Test
    fun `head and style are dropped`() {
        val html = "<html><head><style>p { color: red }</style></head><body><p>Hi</p></body></html>"
        assertThat(html.htmlToPlainText()).isEqualTo("Hi")
    }
}

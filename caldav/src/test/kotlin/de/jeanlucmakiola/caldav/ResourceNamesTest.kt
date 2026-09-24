package de.jeanlucmakiola.caldav

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResourceNamesTest {

    @Test fun `an ordinary uid becomes itself`() {
        assertThat(ResourceNames.forUid("a1b2c3")).isEqualTo("a1b2c3.ics")
    }

    @Test fun `path separators never survive`() {
        // The whole point: a UID is opaque text, an href is a path segment.
        assertThat(ResourceNames.forUid("../../etc/passwd")).doesNotContain("/")
        assertThat(ResourceNames.forUid("a/b")).isEqualTo("a-b.ics")
    }

    @Test fun `at signs are excluded even though they are legal in a path`() {
        assertThat(ResourceNames.forUid("task@example.com")).isEqualTo("task-example.com.ics")
    }

    @Test fun `the basename is capped`() {
        val name = ResourceNames.forUid("x".repeat(500))
        assertThat(name.removeSuffix(".ics")).hasLength(ResourceNames.MAX_BASENAME_BYTES)
    }

    @Test fun `a uid with nothing usable in it falls back to a uuid`() {
        val name = ResourceNames.forUid("///")
        assertThat(name).endsWith(".ics")
        assertThat(name.removeSuffix(".ics")).matches("[0-9a-f-]{36}")
    }

    @Test fun `dots alone do not become a relative path`() {
        assertThat(ResourceNames.forUid("..")).doesNotContain("..")
        assertThat(ResourceNames.forUid(".")).endsWith(".ics")
    }

    @Test fun `random names do not repeat`() {
        assertThat(ResourceNames.random()).isNotEqualTo(ResourceNames.random())
    }

    @Test
    fun `a collection name keeps no extension`() {
        // A collection is a directory, not a file. `.ics` on the end of one is
        // wrong in a way that only shows up when someone looks at the server.
        assertThat(ResourceNames.forCollection("Shopping")).isEqualTo("Shopping")
    }

    @Test
    fun `a name that does not survive sanitising gets a random segment`() {
        // "Einkäufe 🛒" is an ordinary thing to call a list, and none of it is
        // in the safe class. A random segment is the honest answer; the display
        // name the user sees is unaffected.
        val name = ResourceNames.forCollection("🛒")

        assertThat(name).isNotEmpty()
        assertThat(name).doesNotContain("🛒")
        assertThat(name).doesNotContain(ResourceNames.EXTENSION)
    }

    @Test
    fun `two random collection segments differ`() {
        // The retry after Nextcloud's trashbin 403 depends on this: re-creating
        // under a segment used before answers 403 for ever.
        assertThat(ResourceNames.randomCollection())
            .isNotEqualTo(ResourceNames.randomCollection())
    }
}

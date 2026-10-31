package com.aiexile.agendula.data.export

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The export file name. The user picks the folder, so whatever comes out of here
 * is what they will be looking at in a file manager a year from now.
 */
class TaskExporterTest {

    private fun name(listName: String, id: Long = 3L) = TaskExporter.fileNameFor(listName, id)

    @Test
    fun `keeps a plain name readable`() {
        assertThat(name("Groceries")).isEqualTo("Groceries-3.ics")
    }

    @Test
    fun `replaces characters a filesystem would reject`() {
        // SAF can land on FAT32 (an SD card), where these are simply illegal.
        val result = name("Work / Home: notes?")
        assertThat(result).doesNotContain("/")
        assertThat(result).doesNotContain(":")
        assertThat(result).doesNotContain("?")
        assertThat(result).endsWith("-3.ics")
    }

    @Test
    fun `keeps the id so same-named lists cannot collide`() {
        // Two accounts may each have a list called "Personal"; without the id one
        // export would silently overwrite the other.
        assertThat(name("Personal", 1)).isNotEqualTo(name("Personal", 2))
    }

    @Test
    fun `falls back when the name has nothing usable in it`() {
        assertThat(name("///")).isEqualTo("list-3.ics")
        assertThat(name("")).isEqualTo("list-3.ics")
    }

    @Test
    fun `does not leave dangling separators`() {
        assertThat(name("  Shopping  ")).isEqualTo("Shopping-3.ics")
    }

    @Test
    fun `caps the length`() {
        // Many filesystems stop at 255 bytes for a name; a pathological list title
        // should not be the thing that fails an export.
        assertThat(name("x".repeat(500)).length).isAtMost(80)
    }

    @Test
    fun `keeps non-latin names instead of blanking them`() {
        // isLetterOrDigit is Unicode-aware, so these survive rather than collapsing
        // to the "list" fallback.
        assertThat(name("Einkäufe")).isEqualTo("Einkäufe-3.ics")
        assertThat(name("買い物")).isEqualTo("買い物-3.ics")
    }
}

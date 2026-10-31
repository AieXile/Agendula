package com.aiexile.agendula.data.tasks.room

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CategoriesLiftTest {

    @Test
    fun `claims a lone plain line and keeps the rest of the residue`() {
        val result = CategoriesLift.lift("X-FOO:bar\r\nCATEGORIES:Work,Home\r\n")
        assertThat(result.categories).containsExactly("Work", "Home").inOrder()
        assertThat(result.claimed).isTrue()
        assertThat(result.residue).contains("X-FOO:bar")
        assertThat(result.residue).doesNotContain("CATEGORIES")
    }

    @Test
    fun `a residue that was only the line becomes null`() {
        assertThat(CategoriesLift.lift("CATEGORIES:Work\r\n").residue).isNull()
    }

    @Test
    fun `several lines are read but not claimed`() {
        val residue = "CATEGORIES:a\r\nCATEGORIES:b\r\n"
        val result = CategoriesLift.lift(residue)
        assertThat(result.categories).containsExactly("a", "b").inOrder()
        assertThat(result.claimed).isFalse()
        assertThat(result.residue).isEqualTo(residue)
    }

    @Test
    fun `nothing to lift from an empty residue`() {
        assertThat(CategoriesLift.lift(null)).isEqualTo(CategoriesLift.Result(emptyList(), null, claimed = false))
    }
}

package de.jeanlucmakiola.agendula.data.prefs

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.domain.DueWindow
import de.jeanlucmakiola.agendula.domain.Priority
import de.jeanlucmakiola.agendula.domain.SavedFilter
import de.jeanlucmakiola.agendula.domain.TagMatch
import org.junit.jupiter.api.Test

class SavedFilterCodecTest {

    private val filter = SavedFilter(
        id = 3,
        name = "Work | today, 100%",
        due = setOf(DueWindow.TODAY, DueWindow.OVERDUE),
        listIds = setOf(4L, 9L),
        tags = setOf("a,b", "größe", "x|y"),
        tagMatch = TagMatch.ALL,
        minPriority = Priority.MEDIUM,
    )

    @Test
    fun `a filter round-trips, separators and all`() {
        val raw = SavedFilterCodec.upsert(null, filter, "OWN")
        assertThat(SavedFilterCodec.decode(raw, "OWN")).containsExactly(filter)
    }

    @Test
    fun `upsert replaces by id and keeps the order`() {
        val first = filter.copy(id = 1, name = "one")
        var raw = SavedFilterCodec.upsert(null, first, "OWN")
        raw = SavedFilterCodec.upsert(raw, filter, "OWN")
        raw = SavedFilterCodec.upsert(raw, first.copy(name = "renamed"), "OWN")
        assertThat(SavedFilterCodec.decode(raw, "OWN").map { it.name }).containsExactly("renamed", filter.name).inOrder()
    }

    @Test
    fun `list ids belong to their store`() {
        val raw = SavedFilterCodec.upsert(null, filter, "OWN")
        val elsewhere = SavedFilterCodec.decode(raw, "EXTERNAL").single()
        assertThat(elsewhere.listIds).isEmpty()
        assertThat(elsewhere.listsElsewhere).isTrue()
        assertThat(SavedFilterCodec.decode(raw, "OWN").single().listsElsewhere).isFalse()
    }

    @Test
    fun `choosing any list in one store drops the other store's lists`() {
        var raw = SavedFilterCodec.upsert(null, filter, "OWN")
        raw = SavedFilterCodec.upsert(raw, filter.copy(listIds = emptySet(), listsElsewhere = false), "EXTERNAL")
        assertThat(SavedFilterCodec.decode(raw, "OWN").single().anyList).isTrue()
    }

    @Test
    fun `unticking the last list here keeps the other store's lists`() {
        var raw = SavedFilterCodec.upsert(null, filter, "OWN")
        raw = SavedFilterCodec.upsert(raw, filter.copy(listIds = setOf(1L)), "EXTERNAL")
        val external = SavedFilterCodec.decode(raw, "EXTERNAL").single()
        assertThat(external.listsElsewhere).isTrue()
        raw = SavedFilterCodec.upsert(raw, external.copy(listIds = emptySet()), "EXTERNAL")
        assertThat(SavedFilterCodec.decode(raw, "OWN").single().listIds).containsExactly(4L, 9L)
        assertThat(SavedFilterCodec.decode(raw, "EXTERNAL").single().anyList).isFalse()
    }

    @Test
    fun `saving untouched in the other store keeps the lists`() {
        var raw = SavedFilterCodec.upsert(null, filter, "OWN")
        val elsewhere = SavedFilterCodec.decode(raw, "EXTERNAL").single()
        raw = SavedFilterCodec.upsert(raw, elsewhere.copy(name = "renamed"), "EXTERNAL")
        assertThat(SavedFilterCodec.decode(raw, "OWN").single().listIds).containsExactly(4L, 9L)
    }

    @Test
    fun `editing in one store keeps the other store's lists`() {
        var raw = SavedFilterCodec.upsert(null, filter, "OWN")
        raw = SavedFilterCodec.upsert(raw, filter.copy(listIds = setOf(1L)), "EXTERNAL")
        assertThat(SavedFilterCodec.decode(raw, "OWN").single().listIds).containsExactly(4L, 9L)
        assertThat(SavedFilterCodec.decode(raw, "EXTERNAL").single().listIds).containsExactly(1L)
    }

    @Test
    fun `remove drops only that filter`() {
        var raw = SavedFilterCodec.upsert(null, filter.copy(id = 1), "OWN")
        raw = SavedFilterCodec.upsert(raw, filter, "OWN")
        assertThat(SavedFilterCodec.decode(SavedFilterCodec.remove(raw, 1), "OWN").map { it.id }).containsExactly(3L)
    }

    @Test
    fun `broken lines are skipped`() {
        val raw = "nonsense\n" + SavedFilterCodec.upsert(null, filter, "OWN") + "\n7|short\n8|bad%zz||||ANY|NONE"
        assertThat(SavedFilterCodec.decode(raw, "OWN")).containsExactly(filter)
    }
}

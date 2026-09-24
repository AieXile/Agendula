package de.jeanlucmakiola.agendula.data.tasks.room

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class TaskMoveTest {

    private val synced = TaskEntity(
        id = 10,
        listId = SOURCE,
        uid = "u",
        title = "Milk",
        href = "https://s/a/u.ics",
        etag = "e1",
        parentId = 3,
    )

    @Test
    fun `a synced task leaves a tombstone and arrives with no href`() {
        val plan = TaskMove.plan(
            edited = synced.copy(listId = TARGET),
            previous = synced,
            overrides = emptyList(),
            sourceSynced = true,
            targetTombstone = null,
        )

        val moved = plan.update.single()
        assertThat(moved.id).isEqualTo(10)
        assertThat(moved.listId).isEqualTo(TARGET)
        assertThat(moved.href).isNull()
        assertThat(moved.etag).isNull()
        assertThat(moved.isDirty).isTrue()

        // The old collection is owed a conditional DELETE of the old resource.
        val tombstone = plan.tombstone!!
        assertThat(tombstone.id).isEqualTo(0)
        assertThat(tombstone.listId).isEqualTo(SOURCE)
        assertThat(tombstone.href).isEqualTo("https://s/a/u.ics")
        assertThat(tombstone.etag).isEqualTo("e1")
        assertThat(tombstone.isDeleted).isTrue()
        assertThat(tombstone.isDirty).isTrue()
        assertThat(tombstone.parentId).isNull()
    }

    @Test
    fun `overrides move with their master and tombstoned ones are dropped`() {
        val live = override(11, deleted = false)
        val buried = override(12, deleted = true)

        val plan = TaskMove.plan(
            edited = synced.copy(listId = TARGET),
            previous = synced,
            overrides = listOf(live, buried),
            sourceSynced = true,
            targetTombstone = null,
        )

        val movedOverride = plan.update.single { it.id == 11L }
        assertThat(movedOverride.listId).isEqualTo(TARGET)
        assertThat(movedOverride.href).isNull()
        assertThat(movedOverride.isDirty).isTrue()
        assertThat(plan.delete).containsExactly(12L)
    }

    @Test
    fun `a task that never reached the server owes nothing`() {
        val local = synced.copy(href = null, etag = null)

        val plan = TaskMove.plan(local.copy(listId = TARGET), local, emptyList(), true, null)

        assertThat(plan.tombstone).isNull()
    }

    @Test
    fun `leaving a device-only list owes nothing`() {
        // A list detached from a removed account keeps its hrefs, but no server
        // will ever be asked to delete anything for it.
        val plan = TaskMove.plan(synced.copy(listId = TARGET), synced, emptyList(), false, null)

        assertThat(plan.tombstone).isNull()
        assertThat(plan.update.single().href).isNull()
    }

    @Test
    fun `moving back before a sync takes the old resource back`() {
        val tombstone = synced.copy(id = 20, listId = TARGET, isDeleted = true, isDirty = true)
        val elsewhere = synced.copy(listId = SOURCE, href = null, etag = null)

        val plan = TaskMove.plan(
            edited = elsewhere.copy(listId = TARGET),
            previous = elsewhere,
            overrides = emptyList(),
            sourceSynced = true,
            targetTombstone = tombstone,
        )

        val moved = plan.update.single()
        assertThat(moved.href).isEqualTo("https://s/a/u.ics")
        assertThat(moved.etag).isEqualTo("e1")
        assertThat(plan.delete).containsExactly(20L)
        assertThat(plan.tombstone).isNull()
    }

    @Test
    fun `a live row with the same UID is not a tombstone to revive`() {
        val live = synced.copy(id = 20, listId = TARGET)

        val plan = TaskMove.plan(synced.copy(listId = TARGET), synced, emptyList(), true, live)

        assertThat(plan.delete).isEmpty()
        assertThat(plan.update.single().href).isNull()
    }

    private fun override(id: Long, deleted: Boolean) = synced.copy(
        id = id,
        masterId = synced.id,
        recurrenceId = Instant.parse("2026-03-0${id - 9}T12:00:00Z"),
        isDeleted = deleted,
    )

    private companion object {
        const val SOURCE = 1L
        const val TARGET = 2L
    }
}

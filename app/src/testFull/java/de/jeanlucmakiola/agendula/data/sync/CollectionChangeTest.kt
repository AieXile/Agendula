package de.jeanlucmakiola.agendula.data.sync

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.data.tasks.room.TaskListEntity
import de.jeanlucmakiola.caldav.TaskCollection
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test

/** Choosing which lists sync after setup. */
class CollectionChangeTest {

    private val work = collection("work")
    private val home = collection("home")
    private val shared = collection("shared")

    @Test
    fun `a newly ticked collection is attached, an already synced one is not`() {
        val change = AccountRepository.collectionChange(
            offered = listOf(work, home),
            selected = setOf(work.url, home.url),
            synced = listOf(list(1, work)),
        )

        assertThat(change.attach).containsExactly(home)
        assertThat(change.drop).isEmpty()
    }

    @Test
    fun `an unticked synced list is dropped`() {
        val change = AccountRepository.collectionChange(
            offered = listOf(work, home),
            selected = setOf(work.url),
            synced = listOf(list(1, work), list(2, home)),
        )

        assertThat(change.attach).isEmpty()
        assertThat(change.drop.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `a synced list the server did not offer this time is left alone`() {
        // A flaky listing or a share revoked a moment ago is not the user
        // unticking it.
        val change = AccountRepository.collectionChange(
            offered = listOf(work),
            selected = setOf(work.url),
            synced = listOf(list(1, work), list(3, shared)),
        )

        assertThat(change.drop).isEmpty()
    }

    private fun collection(name: String) = TaskCollection(
        url = "https://cloud.example.com/dav/$name/".toHttpUrl(),
        displayName = name,
        color = null,
        readOnly = false,
        isShared = false,
        supportsSyncCollection = true,
        maxResourceSize = null,
    )

    private fun list(id: Long, collection: TaskCollection) = TaskListEntity(
        id = id,
        name = collection.displayName!!,
        color = 0,
        accountId = 1,
        href = collection.url.toString(),
    )
}

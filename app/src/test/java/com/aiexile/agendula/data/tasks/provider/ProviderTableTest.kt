package com.aiexile.agendula.data.tasks.provider

import com.google.common.truth.Truth.assertThat
import com.aiexile.agendula.data.tasks.TasksContract.Instances
import com.aiexile.agendula.data.tasks.TasksContract.Lists
import com.aiexile.agendula.data.tasks.TasksContract.Properties
import com.aiexile.agendula.data.tasks.TasksContract.Tasks
import org.junit.jupiter.api.Test

class ProviderTableTest {

    @Test
    fun `paths follow the OpenTasks contract`() {
        assertThat(ProviderTable.entries.map { it.path })
            .containsExactly("tasklists", "tasks", "instances", "properties").inOrder()
    }

    @Test
    fun `each table keys on its OpenTasks id column`() {
        assertThat(ProviderTable.LISTS.idColumn).isEqualTo(Lists.ID)
        assertThat(ProviderTable.TASKS.idColumn).isEqualTo(Tasks.ID)
        assertThat(ProviderTable.INSTANCES.idColumn).isEqualTo(Tasks.ID)
        assertThat(ProviderTable.PROPERTIES.idColumn).isEqualTo(Properties.PROPERTY_ID)
    }

    @Test
    fun `no table declares a column twice`() {
        ProviderTable.entries.forEach { table ->
            assertThat(table.columns.map { it.first }).containsNoDuplicates()
        }
    }

    @Test
    fun `instances carry every task column`() {
        val tasks = ProviderTable.TASKS.columns.map { it.first }
        val instances = ProviderTable.INSTANCES.columns.map { it.first }

        assertThat(instances).containsAtLeastElementsIn(tasks)
        assertThat(instances).containsAtLeast(Instances.TASK_ID, Instances.INSTANCE_ORIGINAL_TIME)
    }

    @Test
    fun `unsupported OpenTasks columns are declared so a projection naming them succeeds`() {
        assertThat(ProviderTable.TASKS.columns.map { it.first }).containsAtLeast("_dirty", "_sync_id", "sync1", "pinned")
    }

    @Test
    fun `a table URI keeps the caller's selection untouched`() {
        assertThat(ProviderTable.TASKS.selectionFor(null, "title = ?")).isEqualTo("title = ?")
        assertThat(ProviderTable.TASKS.selectionFor(null, null)).isNull()
    }

    @Test
    fun `a row URI narrows to its id`() {
        assertThat(ProviderTable.TASKS.selectionFor(4, null)).isEqualTo("_id = 4")
        assertThat(ProviderTable.PROPERTIES.selectionFor(9, null)).isEqualTo("property_id = 9")
    }

    @Test
    fun `a row URI parenthesises the caller's selection so an OR cannot widen it`() {
        assertThat(ProviderTable.TASKS.selectionFor(4, "a = 1 OR b = 2")).isEqualTo("_id = 4 AND (a = 1 OR b = 2)")
    }

    @Test
    fun `MIME types are OpenTasks' own`() {
        assertThat(ProviderTable.TASKS.mimeType(single = false)).isEqualTo("vnd.android.cursor.dir/org.dmfs.tasks.tasks")
        assertThat(ProviderTable.LISTS.mimeType(single = true))
            .isEqualTo("vnd.android.cursor.item/org.dmfs.tasks.tasklists")
    }
}

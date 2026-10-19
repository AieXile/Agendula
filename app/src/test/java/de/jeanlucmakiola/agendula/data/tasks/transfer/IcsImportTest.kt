package de.jeanlucmakiola.agendula.data.tasks.transfer

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.data.tasks.room.AlarmReference
import de.jeanlucmakiola.agendula.data.tasks.room.TaskAlarmEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TaskEntity
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IcsImportTest {

    private class FakeImportStore(seed: List<TaskEntity> = emptyList()) : IcsImportStore {
        val rows = seed.toMutableList()
        val lists = mutableMapOf<Long, String>()
        private var nextId = 100L

        override fun <T> transaction(block: () -> T): T = block()

        override fun createList(name: String, color: Int): Long {
            val id = 50L + lists.size
            lists[id] = name
            return id
        }

        override fun hasUid(listId: Long, uid: String) = rows.any { it.listId == listId && it.uid == uid }

        override fun hasUidAnywhere(uid: String) = rows.any { it.uid == uid }

        override fun insert(row: TaskEntity): Long {
            val id = nextId++
            rows += row.copy(id = id)
            return id
        }

        val alarms = mutableListOf<TaskAlarmEntity>()

        override fun insertAlarms(taskId: Long, alarms: List<TaskAlarmEntity>) {
            this.alarms += alarms.map { it.copy(taskId = taskId) }
        }

        override fun masterByUid(listId: Long, uid: String) =
            rows.firstOrNull { it.listId == listId && it.uid == uid && it.recurrenceId == null }

        override fun setParent(taskId: Long, parentId: Long?) {
            val index = rows.indexOfFirst { it.id == taskId }
            rows[index] = rows[index].copy(parentId = parentId)
        }

        fun byUid(uid: String) = rows.filter { it.uid == uid }
    }

    private fun calendar(vararg components: String, name: String? = null) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\n")
        if (name != null) append("X-WR-CALNAME:$name\r\n")
        components.forEach { append(it) }
        append("END:VCALENDAR\r\n")
    }

    private fun vtodo(uid: String?, summary: String, vararg extra: String) = buildString {
        append("BEGIN:VTODO\r\n")
        if (uid != null) append("UID:$uid\r\n")
        append("SUMMARY:$summary\r\n")
        extra.forEach { append(it).append("\r\n") }
        append("END:VTODO\r\n")
    }

    private val series = vtodo(
        "series", "Water plants",
        "DTSTART:20260601T080000Z",
        "DUE:20260601T090000Z",
        "RRULE:FREQ=WEEKLY;BYDAY=MO,TH",
        "X-APPLE-SORT-ORDER:7",
        "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Water\r\nTRIGGER:-PT15M\r\nEND:VALARM",
    )
    private val override = vtodo(
        "series", "Water plants (balcony)",
        "RECURRENCE-ID:20260604T080000Z",
        "DTSTART:20260604T100000Z",
        "DUE:20260604T110000Z",
    )
    private val event = "BEGIN:VEVENT\r\nUID:ev\r\nSUMMARY:Meeting\r\nDTSTART:20260601T080000Z\r\nEND:VEVENT\r\n"

    @Test
    fun `a series lands with its override, rule, alarm and unknown properties`() {
        val parsed = IcsImport.parse(listOf(IcsSource("plants.ics", calendar(series, override, event))))
        val store = FakeImportStore()

        val result = IcsImport.write(parsed, ImportTarget.Existing(1L), store)

        assertThat(result.imported).isEqualTo(1)
        assertThat(parsed.skippedComponents).isEqualTo(1)
        val rows = store.byUid("series")
        assertThat(rows).hasSize(2)
        val master = rows.single { it.recurrenceId == null }
        val child = rows.single { it.recurrenceId != null }
        assertThat(master.rrule).isEqualTo("FREQ=WEEKLY;BYDAY=MO,TH")
        assertThat(master.unknownProperties).contains("X-APPLE-SORT-ORDER:7")
        // The alarm is a reminder now, carrying the original for the upload.
        assertThat(master.unknownProperties).doesNotContain("BEGIN:VALARM")
        val alarm = store.alarms.single()
        assertThat(alarm.taskId).isEqualTo(master.id)
        assertThat(alarm.minutesBefore).isEqualTo(15)
        assertThat(alarm.reference).isEqualTo(AlarmReference.START)
        assertThat(alarm.raw).contains("DESCRIPTION:Water")
        assertThat(child.masterId).isEqualTo(master.id)
        assertThat(child.title).isEqualTo("Water plants (balcony)")
        assertThat(rows.all { it.listId == 1L && it.isDirty && it.href == null }).isTrue()
    }

    @Test
    fun `an override keeps its own alarms and an Apple default alarm stays in the residue`() {
        val withAlarm = vtodo(
            "series", "Water plants (balcony)",
            "RECURRENCE-ID:20260604T080000Z",
            "DTSTART:20260604T100000Z",
            "DUE:20260604T110000Z",
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;RELATED=END:-PT1H\r\nEND:VALARM",
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nX-APPLE-DEFAULT-ALARM:TRUE\r\nTRIGGER;RELATED=END:-PT5M\r\nEND:VALARM",
        )
        val parsed = IcsImport.parse(listOf(IcsSource("plants.ics", calendar(series, withAlarm))))
        val store = FakeImportStore()

        IcsImport.write(parsed, ImportTarget.Existing(1L), store)

        val child = store.byUid("series").single { it.recurrenceId != null }
        val own = store.alarms.filter { it.taskId == child.id }
        assertThat(own.map { it.minutesBefore to it.reference }).containsExactly(60 to AlarmReference.DUE)
        assertThat(child.unknownProperties).contains("X-APPLE-DEFAULT-ALARM:TRUE")
    }

    @Test
    fun `the same file in a second list gives the copy alarm identities of its own`() {
        val parsed = IcsImport.parse(listOf(IcsSource("plants.ics", calendar(series))))
        val store = FakeImportStore()

        IcsImport.write(parsed, ImportTarget.Existing(1L), store)
        IcsImport.write(parsed, ImportTarget.Existing(2L), store)

        val (first, second) = store.alarms
        assertThat(first.raw).isNotNull()
        assertThat(second.uid).isNotEqualTo(first.uid)
        // Fresh, so an untouched copy is not re-sent as the original text either.
        assertThat(second.raw).isNull()
        assertThat(second.minutesBefore).isEqualTo(first.minutesBefore)
    }

    @Test
    fun `a parent later in the file is still linked`() {
        val child = vtodo("child", "Buy soil", "RELATED-TO;RELTYPE=PARENT:parent")
        val parent = vtodo("parent", "Repot")
        val store = FakeImportStore()

        IcsImport.write(IcsImport.parse(listOf(IcsSource(null, calendar(child, parent)))), ImportTarget.Existing(1L), store)

        val parentRow = store.byUid("parent").single()
        assertThat(store.byUid("child").single().parentId).isEqualTo(parentRow.id)
    }

    @Test
    fun `a parent already in the target list is linked too`() {
        val existing = TaskEntity(id = 7L, listId = 1L, uid = "parent", title = "Repot")
        val store = FakeImportStore(listOf(existing))
        val child = vtodo("child", "Buy soil", "RELATED-TO:parent")

        IcsImport.write(IcsImport.parse(listOf(IcsSource(null, calendar(child)))), ImportTarget.Existing(1L), store)

        assertThat(store.byUid("child").single().parentId).isEqualTo(7L)
    }

    @Test
    fun `UIDs the target list already holds are skipped`() {
        val store = FakeImportStore(listOf(TaskEntity(id = 1L, listId = 1L, uid = "a", title = "Old")))
        val parsed = IcsImport.parse(listOf(IcsSource(null, calendar(vtodo("a", "New"), vtodo("b", "Other")))))

        val result = IcsImport.write(parsed, ImportTarget.Existing(1L), store)

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.skippedExisting).isEqualTo(1)
        assertThat(store.byUid("a").single().title).isEqualTo("Old")
    }

    @Test
    fun `the same UID in another list is not a duplicate`() {
        val store = FakeImportStore(listOf(TaskEntity(id = 1L, listId = 2L, uid = "a")))
        val parsed = IcsImport.parse(listOf(IcsSource(null, calendar(vtodo("a", "New")))))

        assertThat(IcsImport.write(parsed, ImportTarget.Existing(1L), store).imported).isEqualTo(1)
    }

    @Test
    fun `a component without a UID gets one of its own`() {
        val parsed = IcsImport.parse(listOf(IcsSource(null, calendar(vtodo(null, "One"), vtodo(null, "Two")))))
        val store = FakeImportStore()

        IcsImport.write(parsed, ImportTarget.Existing(1L), store)

        assertThat(store.rows.map { it.uid }.distinct()).hasSize(2)
        assertThat(store.rows.none { it.uid.isBlank() }).isTrue()
    }

    @Test
    fun `a new list is created and named by the caller`() {
        val parsed = IcsImport.parse(listOf(IcsSource(null, calendar(vtodo("a", "Task")))))
        val store = FakeImportStore()

        val result = IcsImport.write(parsed, ImportTarget.NewList("  Garden ", 0), store)

        assertThat(store.lists[result.listId]).isEqualTo("Garden")
        assertThat(store.rows.single().listId).isEqualTo(result.listId)
    }

    @Test
    fun `a zip splits into its ics entries and repeats land once`() {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { out ->
                out.putNextEntry(ZipEntry("Home-1.ics"))
                out.write(calendar(vtodo("a", "A"), name = "Home").toByteArray())
                out.putNextEntry(ZipEntry("Work-2.ics"))
                out.write(calendar(vtodo("a", "A"), vtodo("b", "B"), name = "Work").toByteArray())
                out.putNextEntry(ZipEntry("notes.txt"))
                out.write("ignored".toByteArray())
            }
        }.toByteArray()

        val sources = IcsImport.sourcesOf("agendula-tasks.zip", zip)
        val parsed = IcsImport.parse(sources, "agendula-tasks.zip")

        assertThat(sources.map { it.name }).containsExactly("Home-1.ics", "Work-2.ics")
        assertThat(parsed.tasks.map { it.uid }).containsExactly("a", "b")
        assertThat(parsed.duplicatesInFile).isEqualTo(1)
        // The calendars disagree on a name, so the bundle's own name stands in.
        assertThat(parsed.suggestedListName).isEqualTo("agendula-tasks")
    }

    @Test
    fun `the calendar name wins over the file name`() {
        val parsed = IcsImport.parse(
            listOf(IcsSource("export.ics", calendar(vtodo("a", "A"), name = "Groceries"))),
        )
        assertThat(parsed.suggestedListName).isEqualTo("Groceries")
    }

    @Test
    fun `a plain file with a byte-order mark still parses`() {
        val bytes = ("﻿" + calendar(vtodo("a", "A"))).toByteArray()
        val parsed = IcsImport.parse(IcsImport.sourcesOf("a.ics", bytes))
        assertThat(parsed.tasks).hasSize(1)
        assertThat(parsed.suggestedListName).isEqualTo("a")
    }

    @Test
    fun `garbage counts as unreadable rather than throwing`() {
        val parsed = IcsImport.parse(listOf(IcsSource("x.ics", "not a calendar")))
        assertThat(parsed.tasks).isEmpty()
        assertThat(parsed.unreadableDocuments).isEqualTo(1)
    }
}

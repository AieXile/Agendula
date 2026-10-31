package com.aiexile.agendula.data.demo

import com.aiexile.agendula.data.di.IoDispatcher
import com.aiexile.agendula.data.tasks.TasksRepository
import de.jeanlucmakiola.floret.time.DayWindow
import com.aiexile.agendula.domain.Priority
import com.aiexile.agendula.domain.TaskForm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Debug-only one-shot sample data. Creates a **local, device-only** list
 * ("Agendula Demo") — so nothing syncs to a CalDAV server — and fills it with
 * tasks spread across overdue / today / upcoming / no-date / completed so every
 * smart list shows content. Idempotent: skips if the demo list already exists.
 * Triggered from [com.aiexile.agendula.MainActivity] only in debug builds.
 */
@Singleton
class DemoSeeder @Inject constructor(
    private val repository: TasksRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun seed(): Unit = withContext(io) {
        val lists = runCatching { repository.taskLists().first() }.getOrElse { return@withContext }
        if (lists.any { it.name == DEMO_LIST && it.isLocal }) return@withContext

        val listId = runCatching { repository.createLocalList(DEMO_LIST, DEMO_COLOR) }
            .getOrElse { return@withContext }

        val (todayStart, todayEnd) = DayWindow.today(Clock.System.now(), ZoneId.systemDefault())
        val ts = todayStart.toEpochMilliseconds()
        val te = todayEnd.toEpochMilliseconds()
        val hour = 3_600_000L
        val day = 86_400_000L
        fun at(millis: Long): Instant = Instant.fromEpochMilliseconds(millis)

        repository.createTask(TaskForm(title = "Water the office plants", listId = listId, due = at(ts - hour), priority = Priority.HIGH))
        repository.createTask(TaskForm(title = "Reply to client email", listId = listId, due = at(te - 2 * hour), priority = Priority.MEDIUM))
        repository.createTask(TaskForm(title = "Write stand-up notes", listId = listId, due = at(te - hour)))
        val invoiceId = repository.createTask(TaskForm(title = "Prepare monthly invoice", listId = listId, due = at(te + 2 * day), priority = Priority.MEDIUM))
        repository.createTask(TaskForm(title = "Gather receipts", listId = listId, parentId = invoiceId))
        repository.createTask(TaskForm(title = "Book train tickets", listId = listId, due = at(te + 6 * day), priority = Priority.LOW))
        repository.createTask(TaskForm(title = "Read Compose 1.5 release notes", listId = listId))
        repository.createTask(TaskForm(title = "Sketch the Agendula app icon", listId = listId))

        val done = repository.createTask(TaskForm(title = "Renew domain name", listId = listId, due = at(ts - 2 * day)))
        repository.setCompleted(done, occurrenceStart = null, completed = true)
    }

    private companion object {
        const val DEMO_LIST = "Agendula Demo"
        const val DEMO_COLOR = 0xFF7A5C6B.toInt()
    }
}

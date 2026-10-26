package de.jeanlucmakiola.agendula.data.tasks.provider

import android.content.Context
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import androidx.room.InvalidationTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jeanlucmakiola.agendula.R
import de.jeanlucmakiola.agendula.data.tasks.ProviderResolver
import de.jeanlucmakiola.agendula.data.tasks.StartupGate
import de.jeanlucmakiola.agendula.data.tasks.StorageMode
import de.jeanlucmakiola.agendula.data.tasks.TasksContract
import de.jeanlucmakiola.agendula.data.tasks.room.OBSERVED_TABLES
import de.jeanlucmakiola.agendula.data.tasks.room.TaskAlarmEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TaskEntity
import de.jeanlucmakiola.agendula.data.tasks.room.TaskListRow
import de.jeanlucmakiola.agendula.data.tasks.room.TasksDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Rebuilt at least this often, so the expansion window and `distance_from_current` follow the clock. */
private val MAX_AGE = 1.hours

private val STARTUP_WAIT = 10.seconds

private val NOTIFY_DELAY = 500.milliseconds

/**
 * Serves [AgendulaTaskProvider]: keeps a [SnapshotDatabase] of the Room store,
 * rebuilt lazily after a write or a storage-mode switch, and tells observers of
 * the provider's authority when either happens.
 *
 * Empty in [StorageMode.EXTERNAL]: the Room store is not the user's data then,
 * and clients should read OpenTasks or tasks.org directly.
 */
@Singleton
class ProviderSnapshotSource internal constructor(
    private val context: Context,
    private val database: TasksDatabase,
    private val resolver: ProviderResolver,
    private val awaitStartup: suspend () -> Unit,
    private val clock: Clock = Clock.System,
    private val startupWait: Duration = STARTUP_WAIT,
) {

    @Inject constructor(
        @ApplicationContext context: Context,
        database: TasksDatabase,
        resolver: ProviderResolver,
        startupGate: StartupGate,
    ) : this(context, database, resolver, startupGate::awaitReady)

    private val started = AtomicBoolean(false)
    private val snapshot by lazy { SnapshotDatabase() }
    private val generation = AtomicLong(1)
    private var builtGeneration = 0L
    private var builtAt: Instant? = null

    private val handler = Handler(Looper.getMainLooper())
    private val notify = Runnable {
        context.contentResolver.notifyChange(TasksContract.authorityUri(authority), null)
    }

    val authority: String get() = "${context.packageName}.$AUTHORITY_SUFFIX"

    /** Starts watching for changes. Idempotent; touches the database, so not from the main thread. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        database.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(OBSERVED_TABLES) {
                override fun onInvalidated(tables: Set<String>) {
                    if (resolver.mode() == StorageMode.OWN) changed()
                }
            },
        )
        resolver.onModeChanged(::changed)
    }

    @Synchronized
    fun query(
        table: ProviderTable,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        refresh()
        return snapshot.query(table, projection, selection, selectionArgs, sortOrder)
    }

    /**
     * Runs [block] holding the snapshot's lock, once startup has settled which
     * store is the user's. A write refuses to run without that, where a read
     * falls back to answering from whichever store autoMode() picks.
     */
    fun <T> locked(block: () -> T): T {
        start()
        checkNotNull(runBlocking { withTimeoutOrNull(startupWait) { awaitStartup() } }) {
            "Agendula is still starting; try again"
        }
        return synchronized(this, block)
    }

    /** The [columns] of every row of [table] that [selection] matches, as of now. */
    @Synchronized
    fun rows(table: ProviderTable, columns: List<String>, selection: String?, selectionArgs: Array<out String>?): List<List<Any?>> =
        query(table, columns.toTypedArray(), selection, selectionArgs, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        List(cursor.columnCount) { column ->
                            when (cursor.getType(column)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                                else -> cursor.getString(column)
                            }
                        },
                    )
                }
            }
        }

    /**
     * Marks the snapshot stale at once. A provider write calls this so the next
     * operation of the same batch sees it, before Room's own invalidation lands.
     */
    fun invalidate() {
        generation.incrementAndGet()
    }

    @Synchronized
    private fun refresh() {
        start()
        runBlocking { withTimeoutOrNull(startupWait) { awaitStartup() } }
        val now = clock.now()
        val current = generation.get()
        val expired = builtAt.let { it == null || now - it > MAX_AGE }
        if (current != builtGeneration || expired) {
            snapshot.load(build(now))
            builtGeneration = current
            // Instance ids and distances move with the clock, so a timed rebuild is a change too.
            if (expired && builtAt != null) notifyObservers()
            builtAt = now
        }
    }

    private fun build(now: Instant): ProviderSnapshot {
        if (resolver.mode() != StorageMode.OWN) return ProviderSnapshot.EMPTY
        // Read consistently in one transaction; expand outside it, so the app's
        // own writes do not wait on recurrence expansion.
        val (lists, tasks, alarms) = database.runInTransaction<Triple<List<TaskListRow>, List<TaskEntity>, List<TaskAlarmEntity>>> {
            Triple(database.taskLists().lists(), database.tasks().live(), database.alarms().all())
        }
        return ProviderSnapshot.build(lists, tasks, alarms, context.getString(R.string.account_type), now)
    }

    private fun changed() {
        generation.incrementAndGet()
        notifyObservers()
    }

    /** Coalesced, so a sync committing many small transactions wakes each client once. */
    private fun notifyObservers() {
        handler.removeCallbacks(notify)
        handler.postDelayed(notify, NOTIFY_DELAY.inWholeMilliseconds)
    }

    companion object {
        const val AUTHORITY_SUFFIX = "tasks"
    }
}

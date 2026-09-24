package de.jeanlucmakiola.agendula.data.tasks.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Agendula's own task store. Four tables, designed from
 * what the app actually reads and writes plus RFC 5545's `VTODO`.
 *
 * Schemas are exported to `app/schemas/` and committed, so a future version can
 * be migration-tested against this one.
 */
@Database(
    entities = [
        AccountEntity::class,
        TaskListEntity::class,
        TaskEntity::class,
        TaskAlarmEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TasksDatabase : RoomDatabase() {
    abstract fun taskLists(): TaskListDao
    abstract fun tasks(): TaskDao
    abstract fun alarms(): TaskAlarmDao
    abstract fun accounts(): AccountDao

    companion object {
        const val NAME = "agendula-tasks.db"
    }
}

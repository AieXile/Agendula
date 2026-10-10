package de.jeanlucmakiola.agendula.data.tasks.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
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

        /**
         * v2 adds `categories`. Until then `CATEGORIES` lived in the residue, and
         * an unchanged task is never downloaded again, so the values are lifted
         * out here. Not marked dirty: nothing changed on the server's side.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN categories TEXT NOT NULL DEFAULT ''")
                val lifted = mutableListOf<Pair<Long, CategoriesLift.Result>>()
                db.query(
                    "SELECT id, unknown_properties FROM tasks WHERE unknown_properties LIKE '%CATEGORIES%'",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val result = CategoriesLift.lift(cursor.getString(1))
                        if (result.categories.isNotEmpty()) lifted += cursor.getLong(0) to result
                    }
                }
                lifted.forEach { (id, result) ->
                    val categories = CategoriesConverter.toText(result.categories)
                    if (result.claimed) {
                        db.execSQL(
                            "UPDATE tasks SET categories = ?, unknown_properties = ? WHERE id = ?",
                            arrayOf<Any?>(categories, result.residue, id),
                        )
                    } else {
                        db.execSQL("UPDATE tasks SET categories = ? WHERE id = ?", arrayOf<Any?>(categories, id))
                    }
                }
            }
        }
    }
}

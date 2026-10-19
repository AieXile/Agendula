package de.jeanlucmakiola.agendula.data.tasks.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import kotlin.time.Instant

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
    version = 3,
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

        /**
         * v3 syncs reminders as `VALARM`s, which takes two things the rows did not
         * have: an identity on the wire (`uid`) and the original text (`raw`).
         *
         * Claimable `VALARM`s are lifted out of the residue for every row, synced
         * or not — an unchanged task is never downloaded again, and an imported
         * one never was. Not marked dirty: the server already has them. Where a
         * task held the same lead in both stores (an override forked before v3
         * copies the master's alarms *and* carries its `VALARM`s), the lifted
         * one wins: it is the one with an identity.
         *
         * Reminders that so far lived only on this device are pushed once, by
         * marking their tasks dirty — bounded to open tasks in synced lists whose
         * reminder can still fire, so the upgrade is not a burst of PUTs landing
         * on other clients as long-missed alarms.
         *
         * `tasks.reminders_off` starts at 0 everywhere: until now nothing could
         * have turned a synced task's alarms off.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE task_alarms ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE task_alarms ADD COLUMN raw TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN reminders_off INTEGER NOT NULL DEFAULT 0")
                // A version-4 UUID per row, in one statement: `randomblob` is
                // evaluated per row, and the upgrade runs on app start.
                db.execSQL(
                    """
                    UPDATE task_alarms SET uid =
                        lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' ||
                        substr(lower(hex(randomblob(2))), 2) || '-' ||
                        substr('89ab', 1 + (abs(random()) % 4), 1) || substr(lower(hex(randomblob(2))), 2) || '-' ||
                        lower(hex(randomblob(6)))
                    """.trimIndent(),
                )

                data class Lifted(val taskId: Long, val result: AlarmLift.Result)
                val lifted = mutableListOf<Lifted>()
                db.query(
                    "SELECT id, dtstart, due, is_all_day, rrule, rdate, unknown_properties FROM tasks " +
                        "WHERE unknown_properties LIKE '%VALARM%'",
                ).use { c ->
                    while (c.moveToNext()) {
                        val task = AlarmLift.Task(
                            dtstart = if (c.isNull(1)) null else Instant.fromEpochMilliseconds(c.getLong(1)),
                            due = if (c.isNull(2)) null else Instant.fromEpochMilliseconds(c.getLong(2)),
                            isAllDay = c.getInt(3) != 0,
                            recurring = !c.isNull(4) || !c.isNull(5),
                        )
                        val result = AlarmLift.lift(c.getString(6), task)
                        if (result.alarms.isNotEmpty()) lifted += Lifted(c.getLong(0), result)
                    }
                }
                lifted.forEach { (taskId, result) ->
                    val local = mutableListOf<Triple<Long, Int, String>>()
                    db.query(
                        "SELECT id, minutes_before, reference FROM task_alarms WHERE task_id = ?",
                        arrayOf<Any?>(taskId),
                    ).use { c -> while (c.moveToNext()) local += Triple(c.getLong(0), c.getInt(1), c.getString(2)) }
                    result.alarms.forEach { alarm ->
                        local.firstOrNull { it.second == alarm.minutesBefore && it.third == alarm.reference.name }
                            ?.let { duplicate ->
                                local.remove(duplicate)
                                db.execSQL("DELETE FROM task_alarms WHERE id = ?", arrayOf<Any?>(duplicate.first))
                            }
                        db.execSQL(
                            "INSERT INTO task_alarms (task_id, minutes_before, reference, uid, raw) VALUES (?, ?, ?, ?, ?)",
                            arrayOf<Any?>(
                                taskId,
                                alarm.minutesBefore,
                                alarm.reference.name,
                                alarm.uid ?: UUID.randomUUID().toString(),
                                alarm.raw,
                            ),
                        )
                    }
                    db.execSQL("UPDATE tasks SET unknown_properties = ? WHERE id = ?", arrayOf<Any?>(result.residue, taskId))
                }

                // ⚠️ `raw IS NULL` is "never on the server": everything lifted
                // above carries its original. Status 0/1 are NEEDS-ACTION and
                // IN-PROCESS (`ICalStatus`).
                //
                // ⚠️ Never a list the upload cannot write to. A dirty row is
                // also one the download will not overwrite, so marking a task in
                // a read-only share — or a confidential one, which the upload
                // skips in a shared collection — would freeze it for good: never
                // sent, and the owner's edits never taken. Classification 2 is
                // CONFIDENTIAL.
                //
                // LAST-MODIFIED moves with it: the body changes, and a client
                // reconciling by that stamp would otherwise keep its stale copy.
                val now = System.currentTimeMillis()
                db.execSQL(
                    """
                    UPDATE tasks SET is_dirty = 1, last_modified = ?
                    WHERE is_deleted = 0
                      AND status IN (0, 1)
                      AND (classification IS NULL OR classification != 2)
                      AND list_id IN (SELECT id FROM task_lists WHERE account_id IS NOT NULL AND is_read_only = 0)
                      AND (rrule IS NOT NULL OR rdate IS NOT NULL OR due > ?)
                      AND id IN (SELECT task_id FROM task_alarms WHERE raw IS NULL)
                    """.trimIndent(),
                    arrayOf<Any?>(now, now),
                )
            }
        }
    }
}

package de.jeanlucmakiola.agendula.data.tasks.provider

import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteQueryBuilder
import android.os.Build
import androidx.core.database.sqlite.transaction

/**
 * An in-memory SQLite copy of a [ProviderSnapshot], so a client's own
 * `selection`, `selectionArgs` and `sortOrder` run as SQL exactly as they would
 * against OpenTasks. Holds only what the caller may already read.
 *
 * Not thread-safe; [ProviderSnapshotSource] serialises access.
 */
internal class SnapshotDatabase {

    private val db: SQLiteDatabase = SQLiteDatabase.create(null).apply {
        ProviderTable.entries.forEach { table ->
            execSQL(
                "CREATE TABLE ${table.path} (" +
                    table.columns.joinToString { (name, type) -> "$name $type" } + ")",
            )
        }
    }

    fun load(snapshot: ProviderSnapshot) {
        db.transaction {
            ProviderTable.entries.forEach { table ->
                delete(table.path, null, null)
                snapshot.rows[table].orEmpty().forEach { insertOrThrow(table.path, null, it.toContentValues()) }
            }
        }
    }

    /**
     * Runs the query and copies the result out, so the cursor a client holds
     * survives the next [load].
     */
    fun query(
        table: ProviderTable,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val builder = SQLiteQueryBuilder().apply {
            tables = table.path
            isStrict = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                isStrictColumns = true
                isStrictGrammar = true
            }
            projectionMap = table.columns.associate { (name, _) -> name to name }
        }
        builder.query(db, projection, selection, selectionArgs, null, null, sortOrder).use { source ->
            val copy = MatrixCursor(source.columnNames, source.count)
            while (source.moveToNext()) {
                copy.addRow(
                    Array(source.columnCount) { column ->
                        when (source.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> source.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> source.getDouble(column)
                            Cursor.FIELD_TYPE_BLOB -> source.getBlob(column)
                            else -> source.getString(column)
                        }
                    },
                )
            }
            return copy
        }
    }

    private fun ProviderRow.toContentValues() = ContentValues(size).also { values ->
        forEach { (key, value) ->
            when (value) {
                null -> values.putNull(key)
                is Long -> values.put(key, value)
                is Int -> values.put(key, value)
                is String -> values.put(key, value)
                else -> values.put(key, value.toString())
            }
        }
    }
}

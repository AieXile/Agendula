package com.aiexile.agendula.data.tasks

import android.database.Cursor

/**
 * Domain-shaped seam over a row of provider data, so the mappers can be
 * unit-tested with a Map instead of a real [Cursor] (see MapColumnReader in
 * test sources). Mirrors Calendula's ColumnReader.
 */
interface ColumnReader {
    fun getLong(name: String): Long?
    fun getInt(name: String): Int?
    fun getString(name: String): String?
    fun getBoolean(name: String): Boolean
}

/** Reads the [cursor]'s current row, caching column indices by name. */
class CursorColumnReader(private val cursor: Cursor) : ColumnReader {

    private val indices = HashMap<String, Int>()

    private fun idx(name: String): Int = indices.getOrPut(name) { cursor.getColumnIndex(name) }

    override fun getLong(name: String): Long? {
        val i = idx(name)
        return if (i < 0 || cursor.isNull(i)) null else cursor.getLong(i)
    }

    override fun getInt(name: String): Int? {
        val i = idx(name)
        return if (i < 0 || cursor.isNull(i)) null else cursor.getInt(i)
    }

    override fun getString(name: String): String? {
        val i = idx(name)
        return if (i < 0 || cursor.isNull(i)) null else cursor.getString(i)
    }

    override fun getBoolean(name: String): Boolean = (getInt(name) ?: 0) != 0
}

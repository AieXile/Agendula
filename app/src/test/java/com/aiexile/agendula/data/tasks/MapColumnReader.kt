package com.aiexile.agendula.data.tasks

/** A [ColumnReader] backed by a Map, so mappers test without a real Cursor. */
class MapColumnReader(private val values: Map<String, Any?>) : ColumnReader {
    override fun getLong(name: String): Long? = (values[name] as? Number)?.toLong()
    override fun getInt(name: String): Int? = (values[name] as? Number)?.toInt()
    override fun getString(name: String): String? = values[name] as? String
    override fun getBoolean(name: String): Boolean = ((values[name] as? Number)?.toInt() ?: 0) != 0
}

package com.aiexile.agendula.data.tasks.provider

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Agendula's own store, published under the OpenTasks contract (`tasklists`,
 * `tasks`, `instances`, `properties`) at `<applicationId>.tasks`, so an app that
 * already works with OpenTasks or tasks.org works with Agendula by swapping the
 * authority. Reads are guarded by `<applicationId>.permission.READ_TASKS`, writes
 * by `<applicationId>.permission.WRITE_TASKS`; [ProviderWriter] applies them. See
 * `docs/CONTENT_PROVIDER.md`.
 */
class AgendulaTaskProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderEntryPoint {
        fun snapshotSource(): ProviderSnapshotSource
        fun writer(): ProviderWriter
    }

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(requireNotNull(context), ProviderEntryPoint::class.java)
    }
    private val source: ProviderSnapshotSource by lazy { entryPoint.snapshotSource() }
    private val writer: ProviderWriter by lazy { entryPoint.writer() }

    private val matcher by lazy {
        UriMatcher(UriMatcher.NO_MATCH).apply {
            ProviderTable.entries.forEach { table ->
                addURI(source.authority, table.path, table.ordinal * 2)
                addURI(source.authority, "${table.path}/#", table.ordinal * 2 + 1)
            }
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val (table, id) = match(uri)
        return source.query(table, projection, table.selectionFor(id, selection), selectionArgs, sortOrder).apply {
            setNotificationUri(requireNotNull(context).contentResolver, uri)
        }
    }

    override fun getType(uri: Uri): String {
        val (table, id) = match(uri)
        return table.mimeType(single = id != null)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val (table, id) = match(uri)
        require(id == null) { "Insert into ${table.path}, not into one of its rows" }
        return ContentUris.withAppendedId(uri, writer.insert(table, values.toMap()))
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val (table, id) = match(uri)
        return writer.update(table, values.toMap(), table.selectionFor(id, selection), selectionArgs)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val (table, id) = match(uri)
        return writer.delete(table, table.selectionFor(id, selection), selectionArgs)
    }

    /** All or nothing, as against OpenTasks, and observers hear of it once. */
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> =
        writer.batch { super.applyBatch(operations) }

    private fun match(uri: Uri): Pair<ProviderTable, Long?> {
        val code = matcher.match(uri)
        require(code != UriMatcher.NO_MATCH) { "Unknown URI $uri" }
        val table = ProviderTable.entries[code / 2]
        if (code % 2 == 0) return table to null
        val id = requireNotNull(uri.lastPathSegment?.toLongOrNull()) { "Unknown URI $uri" }
        return table to id
    }

    private fun ContentValues?.toMap(): ProviderValues =
        this?.keySet()?.associateWith { get(it) }.orEmpty()
}

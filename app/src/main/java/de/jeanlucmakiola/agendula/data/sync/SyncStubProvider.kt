package de.jeanlucmakiola.agendula.data.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * A `ContentProvider` that stores nothing.
 *
 * It exists because **a sync adapter is registered against a content
 * authority**, and Agendula publishes no provider — `:provider` was deleted when
 * we took our own Room store. Without an authority there
 * is nothing for `<sync-adapter android:contentAuthority>` to name, nothing for
 * `ContentResolver.requestSync` to address, and nothing for system Settings to
 * render a sync switch against.
 *
 * The sync-adapter registration is not optional:
 * `ContentService.hasAuthorityAccess()` gates `requestSync`,
 * `setSyncAutomatically`, `addPeriodicSync`, `setIsSyncable` and seven more
 * behind a compat change that is **on for targetSdk ≥ 34**, and with nothing
 * registered every one of those calls returns silently — no exception, no log,
 * and it passes on a Robolectric shadow. This provider is the cheapest way to
 * hold up the other end of that requirement.
 *
 * Not exported, and every method is a no-op. Real data lives in Room.
 */
class SyncStubProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}

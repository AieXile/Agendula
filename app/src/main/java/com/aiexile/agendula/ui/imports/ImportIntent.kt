package com.aiexile.agendula.ui.imports

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

private val CALENDAR_TYPES = setOf("text/calendar", "text/x-vcalendar", "application/ics")

/**
 * The `.ics` an incoming intent hands us — `ACTION_VIEW` from a file manager or
 * browser, or `ACTION_SEND` from a share sheet — or null when it is not one.
 * A generic type is accepted only when the file name says `.ics`.
 */
fun importIntentUri(intent: Intent): Uri? {
    val uri = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.data
        else -> null
    } ?: return null
    // A file:// uri needs a storage permission the app does not hold.
    if (uri.scheme != "content") return null
    val type = intent.type?.lowercase()?.substringBefore(';')?.trim()
    val namedIcs = uri.lastPathSegment?.endsWith(".ics", ignoreCase = true) == true
    return uri.takeIf { type in CALENDAR_TYPES || namedIcs }
}

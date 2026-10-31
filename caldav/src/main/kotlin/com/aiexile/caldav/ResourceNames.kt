package com.aiexile.caldav

import java.util.UUID

/**
 * Filenames for calendar resources.
 *
 * ⚠️ **An href is not a UID.** A UID is opaque text chosen by whoever created
 * the task — it may contain slashes, spaces, percent signs or nothing printable
 * at all — while an href is a path segment on someone else's filesystem. Using
 * one as the other is the classic CalDAV client bug: it works against the server
 * you tested on and produces 400s, 403s or silently truncated names elsewhere.
 *
 * The character class is vdirsyncer's, and the exclusion of `@` is deliberate:
 * plenty of UIDs are email-shaped, and `@` in a path segment is legal but
 * routinely mishandled by proxies and by servers that re-parse their own URLs.
 */
object ResourceNames {

    /**
     * Cap on the basename, in bytes.
     *
     * Well under the 255 that most filesystems allow, because the server appends
     * to what we send — Nextcloud's trashbin renames a deleted resource to
     * `<name>-deleted.ics`, and a name that only just fit before deletion does
     * not fit afterwards.
     */
    const val MAX_BASENAME_BYTES = 200

    const val EXTENSION = ".ics"

    private val UNSAFE = Regex("[^A-Za-z0-9_.+-]")

    /**
     * A filename derived from [uid], or a random one when [uid] does not survive
     * sanitisation.
     *
     * The derivation is a convenience for humans reading the collection over
     * WebDAV, never an identity: nothing reads the UID back out of an href.
     */
    fun forUid(uid: String): String {
        val cleaned = UNSAFE.replace(uid, "-")
            .trim('-', '.')
            .take(MAX_BASENAME_BYTES)
            .trimEnd('-', '.')
        return if (cleaned.isEmpty() || cleaned.all { it == '-' }) random() else cleaned + EXTENSION
    }

    /** A fresh name that cannot collide, for the fallback and the 412 retry. */
    fun random(): String = UUID.randomUUID().toString() + EXTENSION

    /**
     * A path segment for a new **collection**, derived from what the user called
     * it.
     *
     * No extension: a collection is a directory, not a file. Derived rather than
     * random for the same reason [forUid] is — someone browsing the account over
     * WebDAV, or in their server's own web UI, should be able to tell which of
     * these is "Shopping" — and, like [forUid], it is a convenience and never an
     * identity: the href is what identifies the collection afterwards.
     *
     * ⚠️ Sanitised to the same class, which matters more here than it does for a
     * resource: a display name is typed by a person, in their own language, and
     * "Einkäufe 🛒" is an ordinary thing to call a list. What survives may be
     * empty, and then a random segment is the honest answer.
     */
    fun forCollection(displayName: String): String {
        val cleaned = UNSAFE.replace(displayName, "-")
            .trim('-', '.')
            .take(MAX_BASENAME_BYTES)
            .trimEnd('-', '.')
        return if (cleaned.isEmpty() || cleaned.all { it == '-' }) randomCollection() else cleaned
    }

    /**
     * A collection segment that cannot collide.
     *
     * ⚠️ Also the retry, and Nextcloud is why it has to exist rather than being
     * a fallback for unprintable names: its trashbin renames a deleted
     * collection instead of removing it, so re-creating one under a name that
     * was used before answers 403 for ever. A fresh segment is the only way
     * back, and the display name is unaffected — two lists may share one.
     */
    fun randomCollection(): String = UUID.randomUUID().toString()
}

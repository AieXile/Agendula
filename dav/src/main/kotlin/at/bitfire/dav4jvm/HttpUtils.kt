/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package at.bitfire.dav4jvm

import okhttp3.HttpUrl
import okhttp3.Response
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.*


object HttpUtils {

    const val httpDateFormatStr = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"

    /**
     * RFC 7231 §7.1.1.1: an HTTP-date is always GMT.
     *
     * ⚠️ Upstream never set this, and the `GMT` in [httpDateFormatStr] is a
     * quoted *literal* — so both formatting and parsing silently used the
     * device's default zone, putting every `getlastmodified` out by the local
     * UTC offset. Upstream's suite does not test date handling at all. Local
     * fix; see dav/PROVENANCE.md change 2.
     */
    private val GMT: TimeZone = TimeZone.getTimeZone("GMT")

    /**
     * Built per call rather than shared. `SimpleDateFormat` is mutable and not
     * thread-safe, and upstream exposed one instance publicly: two sync workers
     * formatting a header concurrently corrupt each other through its `Calendar`,
     * and any caller could `setTimeZone` on it and undo the fix above for
     * everyone. `:dav` is earmarked for floret-kit, where concurrent workers are
     * the normal case. Local change; see dav/PROVENANCE.md change 5.
     *
     * ⚠️ `Locale.US`, not `Locale.ROOT`. On a desktop JVM the two agree, but
     * Android's ICU root locale abbreviates months as `M10`, so every date
     * formatted on a device was unparseable. See change 8.
     */
    private fun httpDateFormat() = SimpleDateFormat(httpDateFormatStr, Locale.US).apply {
        timeZone = GMT
    }

    /**
     * Gets the resource name (the last segment of the path) from an URL.
     * Empty if the resource is the base directory.
     *
     * * `dir` for `https://example.com/dir/`
     * * `file` for `https://example.com/file`
     * * `` for `https://example.com` or  `https://example.com/`
     *
     * @return resource name
     */
    fun fileName(url: HttpUrl): String {
        val pathSegments = url.pathSegments.dropLastWhile { it == "" }
        return pathSegments.lastOrNull() ?: ""
    }

    fun listHeader(response: Response, name: String): Array<String> {
        val value = response.headers(name).joinToString(",")
        return value.split(',').filter { it.isNotEmpty() }.toTypedArray()
    }


    /**
     * Formats a date for use in HTTP headers.
     *
     * @param date date to be formatted
     * @return date in HTTP-date format
     */
    fun formatDate(date: Date): String = httpDateFormat().format(date)

    /**
     * Parses a HTTP-date.
     *
     * @param dateStr date with format specified by RFC 7231 section 7.1.1.1
     * or in one of the obsolete formats (copied from okhttp internal date-parsing class)
     *
     * @return date, or null if date could not be parsed
     */
    /**
     * The formats [parseDate] accepts, in order. RFC 7231 §7.1.1.1 first, then the
     * obsolete ones, then the malformed-but-real ones — copied from OkHttp's
     * internal date parser, which is where upstream took them from.
     */
    private val dateFormats = arrayOf(
        httpDateFormatStr,
        "EEE, dd MMM yyyy HH:mm:ss zzz",    // RFC 822, updated by RFC 1123 with any TZ
        "EEEE, dd-MMM-yy HH:mm:ss zzz",     // RFC 850, obsoleted by RFC 1036 with any TZ
        "EEE MMM d HH:mm:ss yyyy",          // ANSI C's asctime() format
        // Alternative formats.
        "EEE, dd-MMM-yyyy HH:mm:ss z",
        "EEE, dd-MMM-yyyy HH-mm-ss z",
        "EEE, dd MMM yy HH:mm:ss z",
        "EEE dd-MMM-yyyy HH:mm:ss z",
        "EEE dd MMM yyyy HH:mm:ss z",
        "EEE dd-MMM-yyyy HH-mm-ss z",
        "EEE dd-MMM-yy HH:mm:ss z",
        "EEE dd MMM yy HH:mm:ss z",
        "EEE,dd-MMM-yy HH:mm:ss z",
        "EEE,dd-MMM-yyyy HH:mm:ss z",
        "EEE, dd-MM-yyyy HH:mm:ss z",
        /* RI bug 6641315 claims a cookie of this format was once served by www.yahoo.com */
        "EEE MMM d yyyy HH:mm:ss z",
    )

    /**
     * Parses an HTTP-date.
     *
     * @param dateStr date in the format of RFC 7231 §7.1.1.1, or one of the
     * obsolete formats
     *
     * @return the date, or null if none of the formats matched
     */
    fun parseDate(dateStr: String): Date? {
        // Upstream used commons-lang3 DateUtils.parseDate for exactly this loop,
        // and that import was the library's only use of the dependency. See
        // dav/PROVENANCE.md change 1.
        for (format in dateFormats) {
            val parser = SimpleDateFormat(format, Locale.US).apply {
                // GMT is the default for the formats that carry no zone — the
                // quoted-literal 'GMT' one and ANSI C's asctime(). The patterns
                // with a real `z` still take the zone from the input.
                timeZone = GMT
                // The obsolete formats carry two-digit years, and lenient parsing
                // is what lets "dd MMM yy" resolve at all.
                isLenient = true
            }
            val position = ParsePosition(0)
            val parsed = parser.parse(dateStr, position)
            // ⚠️ The whole string must be consumed. `DateUtils.parseDate` enforced
            // this and `SimpleDateFormat.parse` does not: pattern 1 ends in the
            // *literal* 'GMT', so "…07:28:00 GMT+02:00" matches it as a prefix and
            // the offset is silently discarded — reintroducing the exact two-hour
            // error change 2 exists to fix.
            if (parsed != null && position.index == dateStr.length) return parsed
        }
        return null
    }

}
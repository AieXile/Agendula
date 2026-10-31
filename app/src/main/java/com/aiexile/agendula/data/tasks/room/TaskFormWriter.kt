package com.aiexile.agendula.data.tasks.room

import com.aiexile.agendula.domain.TaskForm
import com.aiexile.agendula.domain.TaskStatus
import com.aiexile.agendula.domain.ical.ICalValues
import com.aiexile.agendula.domain.normalizeTags
import com.aiexile.agendula.domain.toICal
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/** Floor to UTC midnight when [allDay], else pass through unchanged. */
internal fun Instant.forAllDay(allDay: Boolean): Instant =
    if (!allDay) this
    else Instant.fromEpochMilliseconds(
        Math.floorDiv(toEpochMilliseconds(), MILLIS_PER_DAY) * MILLIS_PER_DAY,
    )

/**
 * Applies a [TaskForm] to a [TaskEntity]. Pure, so the semantics below are
 * testable on the JVM without a database.
 *
 * This is the Room counterpart of
 * [com.aiexile.agendula.data.tasks.TaskWriteMapper], which stays for
 * External mode. It is a separate object rather than a shared one because most
 * of what that mapper does is work around the provider — clearing `DURATION`
 * because the provider validates a merged row, writing `STATUS` both ways
 * because the provider auto-completes at 100% but will not reopen below it. Here
 * those rules are ours to state directly.
 */
object TaskFormWriter {

    /** A brand-new task. [uid] is minted by the caller and never null. */
    fun newTask(form: TaskForm, uid: String, now: Instant, tzId: String): TaskEntity =
        apply(
            TaskEntity(listId = form.listId, uid = uid, createdAt = now),
            form,
            now,
            tzId,
        )

    /** [current] with [form] applied. Identity, recurrence and sync columns are left alone. */
    fun apply(current: TaskEntity, form: TaskForm, now: Instant, tzId: String): TaskEntity {
        val percent = form.percentComplete?.coerceIn(0, 100)
        val timed = !form.isAllDay && (form.start != null || form.due != null)
        return current.copy(
            listId = form.listId,
            title = form.title.trim(),
            description = form.description?.trim()?.ifBlank { null },
            location = form.location?.trim()?.ifBlank { null },
            url = form.url?.trim()?.ifBlank { null },
            categories = normalizeTags(form.tags),
            priority = form.priority.toICal(),
            percentComplete = percent,
            status = statusFor(percent, current.status),
            completedAt = completedAtFor(percent, current, now),
            dtstart = form.start?.forAllDay(form.isAllDay),
            due = form.due?.forAllDay(form.isAllDay),
            // DUE and DURATION are mutually exclusive (RFC 5545 §3.6.2).
            duration = null,
            isAllDay = form.isAllDay,
            timezone = if (timed) tzId else null,
            parentId = form.parentId?.takeIf { it > 0 },
            remindersOff = if (form.remindersChosen) form.reminders.isEmpty() else current.remindersOff,
            lastModified = now,
            isDirty = true,
        )
    }

    /**
     * [current] with its rule set to [rrule]. Only ever a master or a plain task:
     * an override carries no rule of its own.
     */
    fun withRule(current: TaskEntity, rrule: String?): TaskEntity {
        val rule = rrule?.trim()?.removePrefix("RRULE:")?.ifEmpty { null }
        if (current.masterId != null || rule == current.rrule) return current
        // Dropping the rule ends the series, and its exceptions with it.
        return if (rule == null) current.copy(rrule = null, rdate = null, exdate = null)
        else current.copy(rrule = rule)
    }

    /**
     * [master] edited through one of its occurrences for the whole series: every
     * content field from [form], and the anchor moved by however far the user
     * moved that occurrence, so the other occurrences move with it.
     */
    fun seriesEdit(
        master: TaskEntity,
        occurrenceStart: Instant?,
        occurrenceDue: Instant?,
        form: TaskForm,
        now: Instant,
        tzId: String,
    ): TaskEntity {
        val edited = withRule(apply(master, form, now, tzId), form.rrule)
        val dtstart = shifted(master.dtstart, occurrenceStart, form.start, form.isAllDay)
        val delta = if (dtstart != null && master.dtstart != null) dtstart - master.dtstart else null
        return edited.copy(
            dtstart = dtstart,
            due = shifted(master.due, occurrenceDue, form.due, form.isAllDay),
            // The occurrence's completion is its own, not the series'.
            status = master.status,
            percentComplete = master.percentComplete,
            completedAt = master.completedAt,
            exdate = if (delta == null || delta == kotlin.time.Duration.ZERO) edited.exdate
            else shiftValues(edited.exdate, delta, master.timezone ?: tzId),
        )
    }

    /**
     * An `EXDATE`/`RDATE` list with every value moved by [delta], each kept in the
     * shape it was written in — so a series moved in time keeps its exceptions.
     */
    fun shiftValues(values: String?, delta: kotlin.time.Duration, zone: String): String? =
        mapValues(values, zone) { it + delta }

    /** The values of an `EXDATE`/`RDATE` list before [cut]. */
    fun valuesBefore(values: String?, cut: Instant, zone: String): String? =
        mapValues(values, zone) { it.takeIf { at -> at < cut } }

    /** The values of an `EXDATE`/`RDATE` list at or after [cut]. */
    fun valuesFrom(values: String?, cut: Instant, zone: String): String? =
        mapValues(values, zone) { it.takeIf { at -> at >= cut } }

    private fun mapValues(values: String?, zone: String, map: (Instant) -> Instant?): String? {
        val list = values?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: return values
        return list.mapNotNull { raw ->
            val at = readValue(raw, zone) ?: return@mapNotNull raw
            map(at)?.let { writeValueLike(raw, it, zone) }
        }.joinToString(",").ifEmpty { null }
    }

    private fun readValue(raw: String, zone: String): Instant? = runCatching {
        val z = java.time.ZoneId.of(zone)
        val instant = when {
            !raw.contains('T') -> java.time.LocalDate.parse(raw, VALUE_DATE).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
            raw.endsWith("Z") -> java.time.LocalDateTime.parse(raw.removeSuffix("Z"), VALUE_DATE_TIME).toInstant(java.time.ZoneOffset.UTC)
            else -> java.time.LocalDateTime.parse(raw, VALUE_DATE_TIME).atZone(z).toInstant()
        }
        Instant.fromEpochMilliseconds(instant.toEpochMilli())
    }.getOrNull()

    private fun writeValueLike(raw: String, at: Instant, zone: String): String = when {
        !raw.contains('T') -> ICalValues.formatDate(at)
        raw.endsWith("Z") -> ICalValues.formatDateTime(at, null)
        else -> ICalValues.formatDateTime(at, zone)
    }

    private val VALUE_DATE = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
    private val VALUE_DATE_TIME = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private fun shifted(anchor: Instant?, was: Instant?, now: Instant?, allDay: Boolean): Instant? = when {
        now == null -> null
        anchor == null || was == null -> now.forAllDay(allDay)
        else -> (anchor + (now - was)).forAllDay(allDay)
    }

    /**
     * [master] ended just before [occurrence] — "this and following" cuts the
     * old series here and the rest continues as a new one. Any `COUNT` goes, as
     * `UNTIL` and `COUNT` are mutually exclusive.
     */
    fun endedBefore(master: TaskEntity, occurrence: Instant, now: Instant, floatingZone: String): TaskEntity {
        val rule = master.rrule ?: return master
        return master.copy(
            rrule = ruleEndedBefore(rule, occurrence, master.isAllDay, master.timezone, floatingZone),
            // UNTIL does not bound RDATEs.
            rdate = valuesBefore(master.rdate, occurrence, master.timezone ?: floatingZone),
            lastModified = now,
            isDirty = true,
        )
    }

    /**
     * [rule] with its `COUNT`/`UNTIL` replaced by an `UNTIL` just before
     * [occurrence], in the form the series' own values take.
     */
    fun ruleEndedBefore(
        rule: String,
        occurrence: Instant,
        isAllDay: Boolean,
        timezone: String?,
        floatingZone: String,
    ): String {
        val kept = rule.split(';').filter { part ->
            val key = part.substringBefore('=').trim().uppercase()
            part.isNotBlank() && key != "UNTIL" && key != "COUNT"
        }
        val until = when {
            isAllDay -> ICalValues.formatDate(occurrence - 1.days)
            // A floating series takes a floating UNTIL (RFC 5545 §3.3.10).
            timezone == null -> ICalValues.formatDateTime(occurrence - 1.seconds, floatingZone)
            else -> ICalValues.formatDateTime(occurrence - 1.seconds, null)
        }
        return (kept + "UNTIL=$until").joinToString(";")
    }

    /** [rrule] with its `COUNT` lowered by [spent] occurrences, or unchanged without one. */
    fun remainingCount(rrule: String, spent: Int): String = rrule.split(';').joinToString(";") { part ->
        if (part.substringBefore('=').trim().uppercase() != "COUNT") part
        else "COUNT=${((part.substringAfter('=').trim().toIntOrNull() ?: 1) - spent).coerceAtLeast(1)}"
    }

    /** Cancelled, or back to needs-action; either way no longer complete. */
    fun cancelled(current: TaskEntity, cancelled: Boolean, now: Instant): TaskEntity = current.copy(
        status = if (cancelled) TaskStatus.CANCELLED else TaskStatus.NEEDS_ACTION,
        percentComplete = if (cancelled) current.percentComplete else null,
        completedAt = null,
        lastModified = now,
        isDirty = true,
    )

    /** The completion triple, for the standalone complete toggle. */
    fun completed(current: TaskEntity, completed: Boolean, now: Instant): TaskEntity = current.copy(
        status = if (completed) TaskStatus.COMPLETED else TaskStatus.NEEDS_ACTION,
        percentComplete = if (completed) 100 else null,
        completedAt = if (completed) now else null,
        lastModified = now,
        isDirty = true,
    )

    /**
     * [master] with [occurrence] added to its `EXDATE` — how a single occurrence
     * of a series is deleted.
     *
     * ⚠️ Removing the override row is not a deletion. RFC 5545 reads an absent
     * `RECURRENCE-ID` component as "not overridden", so the master's `RRULE`
     * regenerates that instance; only an `EXDATE` takes it out of the set.
     *
     * @param floatingZone what a series with no `TZID` means by its wall times —
     *   the same zone [com.aiexile.agendula.domain.recurrence
     *   .RecurrenceExpander] resolves it in.
     */
    fun excepting(
        master: TaskEntity,
        occurrence: Instant,
        now: Instant,
        floatingZone: String,
        dirty: Boolean,
    ): TaskEntity {
        val existing = master.exdate?.trim()?.ifEmpty { null }
        val value = exceptionValue(master, occurrence, existing, floatingZone)
        if (existing != null && value in existing.split(',').map(String::trim)) return master
        return master.copy(
            exdate = if (existing == null) value else "$existing,$value",
            lastModified = now,
            isDirty = master.isDirty || dirty,
        )
    }

    /**
     * ⚠️ Written in the shape the list is already in, not in ours.
     *
     * `EXDATE` is stored as the bare property value, so a list is a run of one
     * value type — and lib-recur parses the whole list or none of it. Appending
     * a UTC `…Z` to a run of `DATE`s would drop every exception the series
     * already had, this one included.
     */
    private fun exceptionValue(
        master: TaskEntity,
        occurrence: Instant,
        existing: String?,
        floatingZone: String,
    ): String {
        val sample = existing?.substringBefore(',')?.trim()
        return when {
            sample == null ->
                if (master.isAllDay) ICalValues.formatDate(occurrence)
                else ICalValues.formatDateTime(occurrence, null)
            !sample.contains('T') -> ICalValues.formatDate(occurrence)
            sample.endsWith("Z") -> ICalValues.formatDateTime(occurrence, null)
            // Local wall time: the TZID parameter was dropped on the way in, so
            // the series' own zone is what those values mean.
            else -> ICalValues.formatDateTime(occurrence, master.timezone ?: floatingZone)
        }
    }

    /**
     * A form carrying no percent leaves status alone — the standalone toggle stays
     * authoritative. Otherwise progress and status move together in both
     * directions, which is the asymmetry the provider never had: it auto-completed
     * at 100% but would not reopen below it, stranding a task "done at 75%".
     */
    private fun statusFor(percent: Int?, current: TaskStatus): TaskStatus = when {
        percent == null -> current
        percent >= 100 -> TaskStatus.COMPLETED
        percent > 0 -> TaskStatus.IN_PROCESS
        else -> TaskStatus.NEEDS_ACTION
    }

    private fun completedAtFor(percent: Int?, current: TaskEntity, now: Instant): Instant? = when {
        percent == null -> current.completedAt
        percent >= 100 -> current.completedAt ?: now
        else -> null
    }
}

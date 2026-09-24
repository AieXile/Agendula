package de.jeanlucmakiola.agendula.data.tasks.ical

import de.jeanlucmakiola.agendula.data.tasks.room.TaskEntity
import de.jeanlucmakiola.agendula.domain.PRIORITY_NONE
import de.jeanlucmakiola.agendula.domain.TaskStatus
import de.jeanlucmakiola.agendula.domain.ical.ICalComponent
import de.jeanlucmakiola.agendula.domain.ical.ICalParam
import de.jeanlucmakiola.agendula.domain.ical.ICalParser
import de.jeanlucmakiola.agendula.domain.ical.ICalProperty
import de.jeanlucmakiola.agendula.domain.ical.ICalSerializer
import de.jeanlucmakiola.agendula.domain.ical.ICalValues
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * VTODO ↔ [TaskEntity].
 *
 * ## The residue
 *
 * Everything the mapper does not claim — unknown properties, unknown parameters,
 * `VALARM`s, whole unknown sub-components — is serialised verbatim into
 * [TaskEntity.unknownProperties] and re-emitted on write. RFC 5545 §3.1 requires
 * it ("Applications MUST preserve the value data for x-name and iana-token
 * values that they don't recognize"), and failing it destroys other people's
 * data invisibly — invisible in our own UI precisely because we are the client
 * that does not understand the property.
 *
 * ## What "claimed" means, and why it is narrow
 *
 * The mapper claims a property only when it can **reproduce it exactly** from
 * its columns. Everything else stays in the residue and round-trips untouched:
 *
 * - a value it cannot parse or that is out of range (`PRIORITY:11`,
 *   `PERCENT-COMPLETE:abc`, `STATUS:X-DEFERRED`, `SEQUENCE:x`) — clamping or
 *   defaulting these would be a silent rewrite of somebody's data;
 * - a time it can read but not reproduce — a floating stamp, or a `TZID` this
 *   device's tzdb has never heard of. The column still gets a best-effort
 *   instant so the UI has something to show;
 * - a `DTSTART`/`DUE` pair that disagrees on value type or timezone, where
 *   authoring both from one `is_all_day` flag and one `timezone` column would
 *   destroy the odd one out.
 *
 * ## Residue eviction
 *
 * A suppressed property is only suppressed while it still *agrees* with its
 * column. [contradictsResidue] compares the two on write: if the user has since
 * edited that field, the stale residue copy is evicted and the column is
 * authored. Without this, editing the due date of a task imported with a
 * floating `DUE` would silently do nothing on the server.
 *
 * ## Alarms
 *
 * `VALARM`s round-trip in the residue and are never authored here. Local
 * reminders live in `task_alarms` and are never serialised. The two stores are
 * disjoint, so neither can destroy the other. Merging them is a later
 * decision, not a silent one.
 */
object VTodoMapper {

    /**
     * DAVx5's limit, and ours for the same two reasons: Android's `CursorWindow`
     * row cap, and `CALDAV:max-resource-size`, whose violation is a failed PUT.
     */
    const val MAX_RESIDUE_BYTES = 25 * 1024

    /**
     * Cardinality-one properties the mapper authors from a column, and therefore
     * must not author while the residue still holds the original.
     */
    private val SUPPRESSED_BY_RESIDUE = setOf(
        "DTSTART", "DUE", "COMPLETED", "RECURRENCE-ID", "CREATED", "LAST-MODIFIED",
        "STATUS", "RELATED-TO",
        // ⚠️ The scalars belong here too, and their absence was invisible: the
        // round-trip corpus filters SEQUENCE out of comparison entirely, so a
        // task read from `SEQUENCE:x` was re-emitted carrying *both* `SEQUENCE:0`
        // and `SEQUENCE:x` with nothing to catch it. `write` always authors
        // SEQUENCE, so the duplicate is unconditional — and under
        // `Prefer: handling=strict` sabre will not quietly repair it.
        "SEQUENCE", "PRIORITY", "PERCENT-COMPLETE", "CLASS",
        // Parameterised copies stay in the residue verbatim; authoring the
        // column beside them would emit the same exclusions twice.
        "RDATE", "EXDATE",
    )

    /** What a VTODO yields. Row identity ([TaskEntity.id], `listId`) is the caller's. */
    data class Mapped(
        val entity: TaskEntity,
        /** `RELATED-TO;RELTYPE=PARENT`, for the caller to resolve to a row id. */
        val parentUid: String?,
        /** Set when the source carried no `UID` and the caller must mint one. */
        val uidWasMissing: Boolean,
        /** Residue that exceeded [MAX_RESIDUE_BYTES] and had to be dropped. */
        val droppedResidue: Boolean,
    )

    // ---------------------------------------------------------------- read

    fun read(vtodo: ICalComponent, listId: Long = 0L): Mapped {
        // Claims are tracked by index, not by value: two identical content lines
        // are two pieces of data, and claiming one must not swallow the other on
        // the way into the residue.
        val claimed = mutableSetOf<Int>()
        fun claim(property: ICalProperty?) {
            property?.let { p ->
                vtodo.properties.indexOfFirst { it === p }.takeIf { it >= 0 }?.let(claimed::add)
            }
        }

        /** Claims [property] only if [value] could be read from it. */
        fun <T> take(property: ICalProperty?, value: T?): T? =
            value?.also { claim(property) }

        /**
         * Every `RDATE` / `EXDATE` copy's values, merged.
         *
         * ⚠️ Both are cardinality-**many**, and Apple Calendar writes one line
         * per excluded occurrence. Reading only the first left the rest out of
         * the column the expander works from, so a deleted occurrence
         * reappeared in the list and in its reminders — while round-tripping
         * back to the server perfectly out of the residue, which is what made it
         * invisible.
         *
         * ⚠️ Claimed only when no copy carries a parameter. The column is the
         * bare value, so claiming a `TZID=`- or `VALUE=DATE`-qualified property
         * would drop that qualifier for good: the property never reaches the
         * residue, and `write` re-emits it naked. A floating EXDATE stops
         * matching the instance it excluded, and a bare eight-digit RDATE is
         * read as a malformed DATE-TIME (§3.3.5) — a permanent 415 under the
         * `Prefer: handling=strict` this client sends.
         */
        fun dateList(vtodo: ICalComponent, name: String, claim: (ICalProperty?) -> Unit): String? {
            val copies = vtodo.properties(name)
            if (copies.isEmpty()) return null
            if (copies.all { it.params.isEmpty() }) copies.forEach(claim)
            return copies.joinToString(",") { it.value.trim() }.takeIf { it.isNotEmpty() }
        }

        val uidProperty = vtodo.property("UID")
        // Claimed even when empty: an empty UID must not reach the residue, or
        // write would emit the caller's minted UID alongside the empty one.
        val uid = take(uidProperty, uidProperty?.value?.trim()).orEmpty()

        val dtstart = readTime(vtodo, "DTSTART")
        val due = readTime(vtodo, "DUE")

        // A task's primary stamp is DUE; DTSTART only decides when there is no
        // DUE. Deriving one all-day flag from "either is a DATE" turns a
        // date/date-time pair into two DATEs and destroys the time half.
        val allDay = if (due.present) due.isDate else dtstart.isDate
        val timezone = dtstart.tzid ?: due.tzid

        // Claim a stamp only when the single is_all_day flag and the single
        // timezone column can reproduce it. Otherwise it stays in the residue and
        // is re-emitted exactly as it arrived.
        if (dtstart.reproducible(allDay, timezone)) claim(dtstart.property)
        if (due.reproducible(allDay, timezone)) claim(due.property)

        val recurrenceId = readTime(vtodo, "RECURRENCE-ID")
        if (recurrenceId.reproducible(allDay, timezone)) claim(recurrenceId.property)

        // These three are UTC-only on the way out, so a zoned or date-valued one
        // is not reproducible.
        val completed = readTime(vtodo, "COMPLETED")
        if (completed.isUtcDateTime) claim(completed.property)
        val created = readTime(vtodo, "CREATED")
        if (created.isUtcDateTime) claim(created.property)
        val lastModified = readTime(vtodo, "LAST-MODIFIED")
        if (lastModified.isUtcDateTime) claim(lastModified.property)

        val statusProperty = vtodo.property("STATUS")
        val status = take(
            statusProperty,
            when (statusProperty?.value?.trim()?.uppercase()) {
                "NEEDS-ACTION" -> TaskStatus.NEEDS_ACTION
                "IN-PROCESS" -> TaskStatus.IN_PROCESS
                "COMPLETED" -> TaskStatus.COMPLETED
                "CANCELLED" -> TaskStatus.CANCELLED
                else -> null
            },
        ) ?: TaskStatus.NEEDS_ACTION

        val percentProperty = vtodo.property("PERCENT-COMPLETE")
        val percent = take(
            percentProperty,
            percentProperty?.value?.trim()?.toIntOrNull()?.takeIf { it in 0..100 },
        )

        // 0 = undefined, 1 = highest, 9 = lowest. Stored raw: bucketing on the way
        // in would rewrite a server's PRIORITY:3 as 1 and lose it on write-back.
        val priorityProperty = vtodo.property("PRIORITY")
        val priority = take(
            priorityProperty,
            priorityProperty?.value?.trim()?.toIntOrNull()?.takeIf { it in 0..9 },
        ) ?: PRIORITY_NONE

        val classProperty = vtodo.property("CLASS")
        val classification = take(
            classProperty,
            CLASS_NAMES.indexOf(classProperty?.value?.trim()?.uppercase()).takeIf { it >= 0 },
        )

        val sequenceProperty = vtodo.property("SEQUENCE")
        val sequence = take(
            sequenceProperty,
            sequenceProperty?.value?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
        ) ?: 0

        // RELTYPE defaults to PARENT (§3.2.15), and §3.8.4.5 reads backwards to
        // most implementers: the *referencing* component is the subordinate one,
        // so this points at our parent.
        //
        // Deliberately **not** claimed. TaskEntity holds only a local `parent_id`,
        // so a parent that is not in this store — not fetched yet, in another
        // collection, deleted locally — would leave nothing to write back and the
        // relationship would be destroyed. Keeping it in the residue means the
        // link survives even when the parent row does not.
        val parentUid = vtodo.properties("RELATED-TO")
            .firstOrNull { (it.param("RELTYPE") ?: "PARENT").equals("PARENT", ignoreCase = true) }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }

        val entity = TaskEntity(
            listId = listId,
            uid = uid,
            title = take(vtodo.property("SUMMARY"), vtodo.property("SUMMARY")?.text()),
            description = take(vtodo.property("DESCRIPTION"), vtodo.property("DESCRIPTION")?.text()),
            location = take(vtodo.property("LOCATION"), vtodo.property("LOCATION")?.text()),
            // URI, not TEXT — escaping it would corrupt a query string.
            url = take(vtodo.property("URL"), vtodo.property("URL")?.value?.trim()),
            status = status,
            percentComplete = percent,
            completedAt = completed.instant,
            priority = priority,
            classification = classification,
            dtstart = dtstart.instant,
            due = due.instant,
            duration = take(vtodo.property("DURATION"), vtodo.property("DURATION")?.value?.trim()),
            isAllDay = allDay,
            timezone = timezone,
            rrule = take(vtodo.property("RRULE"), vtodo.property("RRULE")?.value?.trim()),
            rdate = dateList(vtodo, "RDATE", ::claim),
            exdate = dateList(vtodo, "EXDATE", ::claim),
            recurrenceId = recurrenceId.instant,
            createdAt = created.instant,
            lastModified = lastModified.instant,
            // The Organizer's revision counter (§3.8.7.4) — preserved verbatim,
            // never ours to bump.
            sequence = sequence,
        )

        // DTSTAMP is regenerated on every serialisation and carries no state, so
        // it is dropped rather than stored. LAST-MODIFIED, which does carry
        // state, is a column above — conflating the two makes every sync look
        // like an edit.
        val residueProperties = vtodo.properties
            .filterIndexed { index, _ -> index !in claimed }
            .filterNot { it.name.equals("DTSTAMP", ignoreCase = true) }

        val residueText = ICalSerializer.serializeProperties(residueProperties) +
            ICalSerializer.serializeAll(vtodo.components)
        val tooLarge = residueText.toByteArray(Charsets.UTF_8).size > MAX_RESIDUE_BYTES

        return Mapped(
            entity = entity.copy(
                unknownProperties = residueText.takeIf { it.isNotEmpty() && !tooLarge },
            ),
            parentUid = parentUid,
            uidWasMissing = uid.isEmpty(),
            droppedResidue = tooLarge,
        )
    }

    // --------------------------------------------------------------- write

    /**
     * Serialises [entity] back to a VTODO.
     *
     * [parentUid] is the parent's `UID`, which the entity holds only as a row id.
     * [now] is the `DTSTAMP`.
     */
    fun write(
        entity: TaskEntity,
        parentUid: String? = null,
        now: Instant = Clock.System.now(),
    ): ICalComponent {
        val residue = parseResidue(entity.unknownProperties)
        val keptResidue =
            residue.properties.filterNot { contradictsResidue(it, residue, entity, parentUid) }
        val suppressed = keptResidue
            .map { it.name.uppercase() }
            .filterTo(mutableSetOf()) { it in SUPPRESSED_BY_RESIDUE }

        val properties = mutableListOf<ICalProperty>()
        fun add(name: String, value: String?, vararg params: ICalParam) {
            if (value == null || name.uppercase() in suppressed) return
            properties += ICalProperty(name, params.toList(), value)
        }
        fun addTime(name: String, instant: Instant?) {
            if (instant == null || name in suppressed) return
            properties += timeProperty(name, instant, entity)
        }

        add("UID", entity.uid)
        add("DTSTAMP", ICalValues.formatDateTime(now, null))
        add("SEQUENCE", entity.sequence.toString())
        add("SUMMARY", entity.title?.let(ICalValues::escapeText))
        add("DESCRIPTION", entity.description?.let(ICalValues::escapeText))
        add("LOCATION", entity.location?.let(ICalValues::escapeText))
        add("URL", entity.url)

        add("STATUS", entity.status.toICalName())
        add("PERCENT-COMPLETE", entity.percentComplete?.toString())
        // §3.8.2.1: COMPLETED MUST be UTC — no TZID, no floating, no DATE.
        add("COMPLETED", entity.completedAt?.let { ICalValues.formatDateTime(it, null) })
        add("PRIORITY", entity.priority.takeIf { it != PRIORITY_NONE }?.toString())
        add("CLASS", entity.classification?.let { CLASS_NAMES.getOrNull(it) })

        addTime("DTSTART", entity.dtstart)
        addTime("DUE", entity.due)
        add("DURATION", entity.duration)

        add("RRULE", entity.rrule)
        // ⚠️ Authored only when the residue's copies contradicted the column —
        // i.e. we changed the exclusions ourselves. The parameters come from the
        // copy the residue is giving up, so an authored value keeps the TZID the
        // list was written in; with no copy to take them from, a date-shaped
        // value still needs its VALUE=DATE, since §3.3.5's default is DATE-TIME
        // and a bare eight-digit value is malformed.
        dateList("RDATE", entity.rdate, residue, suppressed, properties)
        dateList("EXDATE", entity.exdate, residue, suppressed, properties)
        addTime("RECURRENCE-ID", entity.recurrenceId)

        add("CREATED", entity.createdAt?.let { ICalValues.formatDateTime(it, null) })
        add("LAST-MODIFIED", entity.lastModified?.let { ICalValues.formatDateTime(it, null) })

        if (parentUid != null && "RELATED-TO" !in suppressed) {
            properties += ICalProperty("RELATED-TO", listOf(ICalParam("RELTYPE", "PARENT")), parentUid)
        }

        return ICalComponent(
            name = "VTODO",
            properties = properties + keptResidue,
            components = residue.components,
        )
    }

    private fun dateList(
        name: String,
        value: String?,
        residue: ICalComponent,
        suppressed: Set<String>,
        into: MutableList<ICalProperty>,
    ) {
        if (value == null || name in suppressed) return
        val inherited = residue.properties(name).firstOrNull { it.params.isNotEmpty() }?.params
        into += ICalProperty(name, paramsFor(value, inherited.orEmpty()), value)
    }

    /**
     * The parameters an authored list may carry, given what is in it.
     *
     * ⚠️ Checked against the value rather than copied across. §3.3.5 forbids
     * `TZID` on a `…Z` value and sabre answers 415 for the pair, and a
     * date-shaped value needs `VALUE=DATE` or it is read as a malformed
     * DATE-TIME. A `TZID` inherited from a residue copy therefore only survives
     * where the value is still a local wall time.
     *
     * What this cannot repair is an `EXDATE` whose zone differs from `DTSTART`'s:
     * one `timezone` column describes the task, so a value appended here is
     * written in *that* zone. Legal, and vanishingly rare — every client that
     * writes both writes them alike.
     */
    private fun paramsFor(value: String, inherited: List<ICalParam>): List<ICalParam> {
        val values = value.split(',').map(String::trim).filter(String::isNotEmpty)
        if (values.isEmpty()) return emptyList()
        if (values.none { it.contains('T') }) return listOf(ICalParam("VALUE", "DATE"))
        if (values.any { it.endsWith("Z") }) return emptyList()
        return inherited.filter { it.name.equals("TZID", ignoreCase = true) }
    }

    /**
     * True when a suppressing residue property no longer describes what its
     * column holds — i.e. the user has edited that field since it was imported,
     * so the stale copy must go and the column must be authored instead.
     */
    private fun contradictsResidue(
        property: ICalProperty,
        residue: ICalComponent,
        entity: TaskEntity,
        parentUid: String?,
    ): Boolean {
        // Cardinality-many and list-valued: the column carries every copy's
        // values merged, so it contradicts only when that whole set has changed
        // — which is what deleting an occurrence does, and nothing else here
        // does. All the copies are then dropped together and one authored line
        // carries the lot, parameters included.
        fun listChanged(column: String?): Boolean {
            fun split(text: String?) =
                text.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            return split(column) != residue.properties(property.name).flatMap { split(it.value) }
        }
        fun sameInstant(column: Instant?) =
            ICalValues.readInstant(ICalValues.parseTime(property)) == column

        return when (property.name.uppercase()) {
            "DTSTART" -> !sameInstant(entity.dtstart)
            "DUE" -> !sameInstant(entity.due)
            "COMPLETED" -> !sameInstant(entity.completedAt)
            "RECURRENCE-ID" -> !sameInstant(entity.recurrenceId)
            "RDATE" -> listChanged(entity.rdate)
            "EXDATE" -> listChanged(entity.exdate)
            "CREATED" -> !sameInstant(entity.createdAt)
            "LAST-MODIFIED" -> !sameInstant(entity.lastModified)
            // The residue only ever holds a STATUS we could not read, which left
            // the column at its NEEDS-ACTION fallback. Anything else means the
            // user has since set a real status.
            "STATUS" -> entity.status != TaskStatus.NEEDS_ACTION
            // A null parentUid is "the parent is not in this store", not "there is
            // no parent" — dropping the link there would destroy a relationship
            // over a row we simply have not fetched.
            //
            // ⚠️ Scoped to RELTYPE=PARENT, exactly as the read side is. Nothing
            // here models CHILD or SIBLING, and no RELATED-TO is ever claimed,
            // so every one of them lives in the residue — where an unscoped
            // comparison read a CHILD link as "a PARENT link that disagrees with
            // the column" and dropped it on the next PUT.
            "RELATED-TO" ->
                (property.param("RELTYPE") ?: "PARENT").equals("PARENT", ignoreCase = true) &&
                    parentUid != null && property.value.trim() != parentUid
            // Each of these reaches the residue only when it could not be parsed,
            // which left the column at its fallback. A column that has since moved
            // off that fallback is the user's edit, and it wins.
            "PRIORITY" -> entity.priority != PRIORITY_NONE
            "PERCENT-COMPLETE" -> entity.percentComplete != null
            "CLASS" -> entity.classification != null
            // Not SEQUENCE: it is the organiser's counter and never ours to bump,
            // so the column stays at its fallback and the residue always wins.
            else -> false
        }
    }

    /**
     * A `VALARM` with `TRIGGER;RELATED=END` needs `DUE`, or `DTSTART` plus
     * `DURATION` (§3.8.6.3). Clearing the due date on a task that has an
     * end-relative reminder produces a resource the server rejects permanently —
     * and it is reachable from ordinary UI actions, so it is checked before PUT
     * rather than discovered as a 415.
     */
    fun validate(vtodo: ICalComponent): List<String> {
        val problems = mutableListOf<String>()
        val due = vtodo.property("DUE")
        val dtstart = vtodo.property("DTSTART")
        val duration = vtodo.property("DURATION")

        if (due != null && dtstart != null) {
            val start = ICalValues.readInstant(ICalValues.parseTime(dtstart))
            val end = ICalValues.readInstant(ICalValues.parseTime(due))
            // sabre answers 415 for both of these, not a 4xx that names them.
            if (start != null && end != null && end < start) problems += "DUE precedes DTSTART"
            if (isDateValue(dtstart) != isDateValue(due)) {
                problems += "DTSTART and DUE disagree on value type"
            }
        }

        if (due == null && (dtstart == null || duration == null)) {
            val endRelative = vtodo.components("VALARM").any { alarm ->
                alarm.property("TRIGGER")?.param("RELATED").equals("END", ignoreCase = true)
            }
            if (endRelative) problems += "TRIGGER;RELATED=END with neither DUE nor DTSTART+DURATION"
        }

        if (vtodo.property("METHOD") != null) problems += "METHOD is not allowed on a stored resource"
        return problems
    }

    // -------------------------------------------------------------- helpers

    private val CLASS_NAMES = listOf("PUBLIC", "PRIVATE", "CONFIDENTIAL")

    private class TimeRead(
        val property: ICalProperty?,
        val instant: Instant?,
        val tzid: String?,
        val isDate: Boolean,
        val representable: Boolean,
    ) {
        val present get() = property != null

        /** True when one `is_all_day` flag and one `timezone` column reproduce it. */
        fun reproducible(allDay: Boolean, timezone: String?) =
            present && representable && isDate == allDay && tzid == timezone

        /** True when it is a UTC date-time — the only form we author for these. */
        val isUtcDateTime get() = present && representable && !isDate && tzid == null
    }

    private fun readTime(vtodo: ICalComponent, name: String): TimeRead {
        val property = vtodo.property(name) ?: return TimeRead(null, null, null, false, false)
        return when (val value = ICalValues.parseTime(property)) {
            is ICalValues.TimeValue.Date -> TimeRead(property, value.instant, null, true, true)
            is ICalValues.TimeValue.Timed -> TimeRead(property, value.instant, value.tzid, false, true)
            // Readable but not reproducible: the column gets the best-effort
            // instant, the property round-trips from the residue verbatim.
            is ICalValues.TimeValue.Unrepresentable ->
                TimeRead(property, value.instant, null, false, false)
        }
    }

    private fun timeProperty(name: String, instant: Instant, entity: TaskEntity): ICalProperty {
        if (entity.isAllDay) {
            return ICalProperty(
                name,
                listOf(ICalParam("VALUE", "DATE")),
                ICalValues.formatDate(instant),
            )
        }
        // Resolve the zone once. Letting the parameter and the value each decide
        // separately produces TZID on a `…Z` value, which §3.3.5 forbids and
        // sabre answers 415 for.
        val tzid = entity.timezone?.takeIf { runCatching { ZoneId.of(it) }.isSuccess }
        val params = if (tzid == null) emptyList() else listOf(ICalParam("TZID", tzid))
        return ICalProperty(name, params, ICalValues.formatDateTime(instant, tzid))
    }

    /** The same DATE test [ICalValues.parseTime] applies, so the two cannot disagree. */
    private fun isDateValue(property: ICalProperty): Boolean =
        ICalValues.parseTime(property) is ICalValues.TimeValue.Date

    private fun ICalProperty.text(): String = ICalValues.unescapeText(value)

    private fun parseResidue(text: String?): ICalComponent {
        if (text.isNullOrEmpty()) return ICalComponent("VTODO")
        // The residue is stored as bare properties followed by whole sub-component
        // blocks, so it parses as the body of a VTODO with the wrapper restored.
        return runCatching {
            ICalParser.parse("BEGIN:VTODO\r\n$text\r\nEND:VTODO\r\n")
        }.getOrElse { ICalComponent("VTODO") }
    }

    private fun TaskStatus.toICalName(): String = when (this) {
        TaskStatus.NEEDS_ACTION -> "NEEDS-ACTION"
        TaskStatus.IN_PROCESS -> "IN-PROCESS"
        TaskStatus.COMPLETED -> "COMPLETED"
        TaskStatus.CANCELLED -> "CANCELLED"
    }
}

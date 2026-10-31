package com.aiexile.agendula.data.tasks.ical

import com.aiexile.agendula.domain.ical.ICalComponent

/**
 * Refuses a resource before it is uploaded.
 *
 * ⚠️ This exists because **sabre answers 415 for things ordinary UI actions
 * produce**, and a 415 is not recoverable by retrying: the row stays dirty, the
 * next sync sends the same bytes, and the user sees a task that never leaves the
 * device with no explanation. Catching it here turns a permanent silent failure
 * into one message naming the field.
 *
 * Every rule below is a real sabre rejection, not defensive tidiness.
 */
object ResourceValidator {

    /** Why a resource cannot be uploaded, in words that name the offending field. */
    @JvmInline
    value class Rejection(val reason: String)

    /** Null when [calendar] may be uploaded. */
    fun validate(calendar: ICalComponent): Rejection? {
        // An object carrying METHOD is an iTIP *message*, not calendar data.
        // RFC 4791 §4.1 forbids it outright in a calendar object resource.
        calendar.property("METHOD")?.let {
            return Rejection("carries METHOD:${it.value}, which makes it a scheduling message")
        }

        val todos = calendar.components("VTODO")
        if (todos.isEmpty()) return Rejection("contains no VTODO")

        // ⚠️ Mixed component types in one resource. A VEVENT that arrived in the
        // same body and was never modelled must not be re-emitted next to a
        // VTODO — RFC 4791 §4.1 allows only one component type per resource.
        val foreign = calendar.components
            .map { it.name.uppercase() }
            .filterNot { it == "VTODO" || it == "VTIMEZONE" }
            .distinct()
        if (foreign.isNotEmpty()) {
            return Rejection("mixes VTODO with ${foreign.joinToString(", ")}")
        }

        // ⚠️ Overrides with no master. A `RECURRENCE-ID` names an instance *of*
        // a series, so a body holding only overrides describes instances of
        // something that is not in the resource — RFC 4791 §4.1 asks for the
        // recurring component and its overridden instances, not the instances
        // alone. This is the shape a partly tombstoned series used to serialise
        // to, and no server rejects it in a way that names the cause.
        if (todos.all { it.property("RECURRENCE-ID") != null }) {
            return Rejection("holds overridden instances but not the task they override")
        }

        // One resource, one UID — the constraint that makes "fork the conflicting
        // edit into the same resource" impossible, and the one servers enforce.
        val uids = todos.map { it.property("UID")?.value.orEmpty() }.distinct()
        if (uids.size > 1) return Rejection("holds ${uids.size} different UIDs")
        if (uids.singleOrNull().isNullOrBlank()) return Rejection("has no UID")

        // ⚠️ A TZID without a leading solidus must reference a VTIMEZONE in the
        // same object (RFC 5545 §3.2.19). We regenerate definitions from
        // `java.time`, which cannot resolve a non-IANA id — a Windows zone name
        // that arrived from another client and survives in the residue produces a
        // reference with nothing behind it. `Prefer: handling=strict` turns that
        // from a server-side repair into a rejection, so it is caught by name
        // here instead of as an unexplained 415.
        val defined = calendar.components("VTIMEZONE")
            .mapNotNull { it.property("TZID")?.value }
            .toSet()
        val undefined = todos.flatMap(::tzidsIn)
            // ⚠️ The solidus form is exempt, and the rule above says so: §3.2.19
            // requires a local VTIMEZONE only for a TZID *without* a leading
            // solidus, because the prefix marks a globally defined identifier.
            // Thunderbird writes `/mozilla.org/20050126_1/Europe/Berlin` on every
            // zoned task, `java.time` cannot resolve it, and rejecting it would
            // quarantine every Lightning-authored task permanently.
            .filterNot { it.startsWith('/') }
            .filterNot { it in defined }
            .distinct()
        if (undefined.isNotEmpty()) {
            return Rejection("references the unknown time zone ${undefined.first()}")
        }

        return todos.firstNotNullOfOrNull(::validateTodo)
    }

    /**
     * ⚠️ The per-component rules are [VTodoMapper.validate]'s, not a second copy.
     *
     * Two value-type tests that disagree is worse than one: `ResourceValidator`
     * originally tested only `VALUE=DATE`, while the mapper also treats a bare
     * eight-digit value as a DATE, so a residue `DTSTART:20260101` beside an
     * authored `DUE;VALUE=DATE:20260102` was rejected as a mismatch and never
     * left the device.
     */
    private fun validateTodo(todo: ICalComponent): Rejection? {
        if (todo.property("DUE") != null && todo.property("DURATION") != null) {
            // RFC 5545 §3.6.2: DUE and DURATION are mutually exclusive.
            return Rejection("has both DUE and DURATION")
        }
        return VTodoMapper.validate(todo).firstOrNull()?.let(::Rejection)
    }

    private fun tzidsIn(component: ICalComponent): List<String> =
        component.properties.mapNotNull { it.param("TZID")?.takeIf(String::isNotBlank) } +
            component.components.flatMap(::tzidsIn)
}

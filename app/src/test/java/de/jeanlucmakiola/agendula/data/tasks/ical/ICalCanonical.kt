package de.jeanlucmakiola.agendula.data.tasks.ical

import de.jeanlucmakiola.agendula.domain.ical.ICalComponent
import de.jeanlucmakiola.agendula.domain.ical.ICalValues

/**
 * Reduces a component tree to the multiset of facts a faithful round-trip must
 * preserve, so two serialisations can be compared for *meaning* rather than for
 * bytes.
 *
 * Byte-stability is unachievable, and
 * asserting it produces a corpus which gets normalised until it tests nothing.
 * Six things legitimately differ across a round-trip — `PRODID` must change,
 * fold position carries no information, parameter quoting is optional, property
 * order within a component is unconstrained, `DTSTAMP` is regenerated, and
 * `VTIMEZONE` is re-emitted — so those are enumerated here and **nothing else
 * may differ**. In particular the unfolded value octets of every property are
 * compared exactly.
 */
object ICalCanonical {

    /** Properties whose value is allowed to differ across a round-trip. */
    private val ALLOWED_TO_DIFFER = setOf("PRODID", "DTSTAMP", "LAST-MODIFIED", "SEQUENCE")

    /** Re-emitted from its own rules rather than preserved verbatim. */
    private val OPAQUE_COMPONENTS = setOf("VTIMEZONE")

    /**
     * The one value-level equivalence, and it is an equivalence rather than a
     * concession: RFC 5545 section 3.8.1.11 gives a to-do no default `STATUS`,
     * and an absent one is universally read as needing action. Normalising
     * between the two directions loses nothing, so both are dropped before
     * comparison. A `STATUS` that is *not* `NEEDS-ACTION` is compared normally,
     * so losing a completion still fails.
     */
    private fun isNeutralStatus(name: String, value: String) =
        name == "STATUS" && value == "NEEDS-ACTION"

    data class Fact(
        val path: String,
        val name: String,
        val params: Map<String, List<String>>,
        val value: String,
    )

    fun facts(component: ICalComponent, path: String = ""): List<Fact> {
        if (component.name in OPAQUE_COMPONENTS) return emptyList()
        val here = if (path.isEmpty()) component.name else "$path/${component.name}"
        val own = component.properties
            .filterNot { it.name.uppercase() in ALLOWED_TO_DIFFER }
            .filterNot { isNeutralStatus(it.name.uppercase(), it.value.trim()) }
            .map { property ->
                Fact(
                    path = here,
                    name = property.name.uppercase(),
                    params = property.params
                        .associate { it.name.uppercase() to it.values }
                        .toSortedMap()
                        .toMap(),
                    // The comparison unit: the unfolded,
                    // **unescaped** value. `\N` and `\n` are the same escape, and a
                    // bare comma in a TEXT value is malformed input that we repair
                    // on write — both are equivalences, not losses. Anything that
                    // actually changes the value still fails.
                    value = ICalValues.unescapeText(property.value),
                )
            }
        return own + component.components.flatMap { facts(it, here) }
    }

    /** Facts present in [before] but missing from [after] — i.e. what was lost. */
    fun lost(before: ICalComponent, after: ICalComponent): List<Fact> {
        val remaining = facts(after).toMutableList()
        return facts(before).filterNot { remaining.remove(it) }
    }

    /** Facts [after] gained that [before] never had. */
    fun invented(before: ICalComponent, after: ICalComponent): List<Fact> = lost(after, before)
}

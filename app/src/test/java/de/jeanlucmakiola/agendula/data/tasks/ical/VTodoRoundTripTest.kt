package de.jeanlucmakiola.agendula.data.tasks.ical

import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.domain.ical.ICalComponent
import de.jeanlucmakiola.agendula.domain.ical.ICalParser
import de.jeanlucmakiola.agendula.domain.ical.ICalSerializer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.time.Instant

/**
 * The corpus in `app/src/test/resources/vtodo/` is the mapper's specification.
 *
 * The requirement being encoded is RFC 5545 §3.1: *"Applications MUST preserve
 * the value data for x-name and iana-token values that they don't recognize."*
 * Failing it destroys other people's data invisibly — invisible in our own UI
 * precisely because we are the client that does not understand the property.
 */
class VTodoRoundTripTest {

    private val fixtures = listOf(
        "nextcloud-shared-confidential",
        "recurring-master-overrides",
        "valarm-unknown-nested",
        "unknown-component",
        "unknown-tzid",
        "uid-special-chars",
        "rrule-due-no-dtstart",
        "moz-alarm-props",
        "apple-sort-order",
        "floating-time",
        "completion-model-a",
        "completion-model-b",
        "completion-model-c",
        "completion-model-d",
        "quoted-param-colon",
        "folded-utf8",
        "mixed-value-types",
        "malformed-scalars",
        "two-timezones",
        "categories-shapes",
        "categories-split",
    )

    @TestFactory
    fun `every fixture round-trips without losing a property`(): List<DynamicTest> =
        fixtures.map { name ->
            DynamicTest.dynamicTest(name) {
                val todos = vtodosOf(name)
                assertThat(todos).isNotEmpty()
                todos.forEach { original ->
                    val roundTripped = roundTrip(original)
                    assertThat(ICalCanonical.lost(original, roundTripped)).isEmpty()
                    assertThat(ICalCanonical.invented(original, roundTripped)).isEmpty()
                }
            }
        }

    @Test
    fun `an unknown property nested inside a VALARM survives`() {
        val original = vtodosOf("valarm-unknown-nested").single()
        val roundTripped = roundTrip(original)

        val alarm = roundTripped.components("VALARM").single()
        assertThat(alarm.property("X-WR-ALARMUID")?.value)
            .isEqualTo("8E6C4A1E-0000-4C1B-9B1A-3F5F4C2D9A11")
        // RFC 9074's alarm properties are what other clients now write; a flat
        // property-per-row model would have dropped this one with the nesting.
        assertThat(alarm.property("ACKNOWLEDGED")?.value).isEqualTo("20260901T113000Z")
    }

    @Test
    fun `an entirely unknown sub-component survives`() {
        val roundTripped = roundTrip(vtodosOf("unknown-component").single())
        val vendor = roundTripped.components("X-VENDOR-METADATA").single()
        assertThat(vendor.property("X-VENDOR-KEY")?.value).isEqualTo("project-alpha")
    }

    @Test
    fun `a TZID the device does not know is re-emitted verbatim, not guessed`() {
        val original = vtodosOf("unknown-tzid").single()
        val mapped = VTodoMapper.read(original)
        val roundTripped = roundTrip(original)

        val due = roundTripped.property("DUE")
        assertThat(due?.param("TZID")).isEqualTo("Custom/Company-HQ")
        assertThat(due?.value).isEqualTo("20260910T170000")
        // Exactly one DUE: the residue owns it, so the mapper must not also
        // author one from its column.
        assertThat(roundTripped.properties("DUE")).hasSize(1)
        // The column still gets a best-effort instant so the UI has something.
        assertThat(mapped.entity.due).isNotNull()
    }

    @Test
    fun `a floating time is re-emitted verbatim, not pinned to a zone`() {
        val roundTripped = roundTrip(vtodosOf("floating-time").single())
        assertThat(roundTripped.property("DTSTART")?.value).isEqualTo("20260905T070000")
        assertThat(roundTripped.property("DTSTART")?.params).isEmpty()
        assertThat(roundTripped.properties("DTSTART")).hasSize(1)
    }

    @Test
    fun `X-MOZ alarm bookkeeping survives, because dropping it causes alarm storms`() {
        val roundTripped = roundTrip(vtodosOf("moz-alarm-props").single())
        assertThat(roundTripped.property("X-MOZ-LASTACK")?.value).isEqualTo("20260904T090000Z")
        assertThat(roundTripped.property("X-MOZ-SNOOZE-TIME")?.value).isEqualTo("20260905T093000Z")
    }

    @Test
    fun `X-APPLE-SORT-ORDER is preserved and never interpreted`() {
        val original = vtodosOf("apple-sort-order").single()
        val mapped = VTodoMapper.read(original)
        assertThat(roundTrip(original).property("X-APPLE-SORT-ORDER")?.value).isEqualTo("734829163")
        // Ours is local ordering and has nothing to do with Apple's.
        assertThat(mapped.entity.sortOrder).isEqualTo(0)
    }

    @Test
    fun `a quoted parameter containing a colon and a comma survives`() {
        val roundTripped = roundTrip(vtodosOf("quoted-param-colon").single())
        val attendee = roundTripped.property("ATTENDEE")
        assertThat(attendee?.param("CN")).isEqualTo("Smith, J:r")
        assertThat(attendee?.value).isEqualTo("mailto:jr@example.com")
        // And it is re-quoted on the way out, or nothing can read it back.
        assertThat(ICalSerializer.render(attendee!!)).contains("CN=\"Smith, J:r\"")
    }

    @Test
    fun `residue past the size cap is dropped loudly rather than truncated`() {
        val huge = "X-BULK:" + "a".repeat(VTodoMapper.MAX_RESIDUE_BYTES + 1)
        val vtodo = ICalParser.parse(
            "BEGIN:VTODO\r\nUID:big-001\r\nSUMMARY:Big\r\n$huge\r\nEND:VTODO\r\n",
        )
        val mapped = VTodoMapper.read(vtodo)

        assertThat(mapped.droppedResidue).isTrue()
        assertThat(mapped.entity.unknownProperties).isNull()
    }

    private fun roundTrip(vtodo: ICalComponent): ICalComponent {
        val mapped = VTodoMapper.read(vtodo)
        val written = VTodoMapper.write(
            entity = mapped.entity,
            parentUid = mapped.parentUid,
            now = Instant.fromEpochMilliseconds(0),
        )
        // Serialise and re-parse rather than comparing the tree directly: folding
        // and parameter quoting are where a serialiser loses data, and skipping
        // the text would skip exactly that.
        return ICalParser.parse(
            "BEGIN:VCALENDAR\r\n" + ICalSerializer.serialize(written) + "END:VCALENDAR\r\n",
        ).components("VTODO").single()
    }

    private fun vtodosOf(name: String): List<ICalComponent> {
        val text = checkNotNull(javaClass.getResourceAsStream("/vtodo/$name.ics")) {
            "missing fixture $name.ics"
        }.bufferedReader().readText()
        return ICalParser.parse(text).components("VTODO")
    }
}

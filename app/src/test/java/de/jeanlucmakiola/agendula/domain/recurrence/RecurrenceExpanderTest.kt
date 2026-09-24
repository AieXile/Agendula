package de.jeanlucmakiola.agendula.domain.recurrence

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.time.Instant

/**
 * The provider only ever materialised the single next occurrence, so there is no
 * provider behaviour to compare multi-occurrence expansion against. These cases
 * assert against RFC 5545 §3.8.5 directly.
 */
class RecurrenceExpanderTest {

    private val berlin = "Europe/Berlin"
    private val newYork = ZoneId.of("America/New_York")

    private fun at(text: String) = Instant.parse(text)

    private fun spec(
        rrule: String? = null,
        rdate: String? = null,
        exdate: String? = null,
        anchor: String,
        isAllDay: Boolean = false,
        timeZone: String? = berlin,
    ) = RecurrenceSpec(rrule, rdate, exdate, at(anchor), isAllDay, timeZone)

    private fun window(
        from: String = "2000-01-01T00:00:00Z",
        until: String = "2100-01-01T00:00:00Z",
        max: Int = 500,
    ) = ExpansionWindow(at(from), at(until), max)

    private fun expand(
        spec: RecurrenceSpec,
        window: ExpansionWindow = window(),
        floatingZone: ZoneId = newYork,
    ) = RecurrenceExpander.expand(spec, window, floatingZone).map { it.toString() }

    // --- frequencies ---------------------------------------------------------

    @Test
    fun `daily rule yields consecutive days at the same local time`() {
        val result = expand(spec(rrule = "FREQ=DAILY;COUNT=3", anchor = "2025-01-07T08:00:00Z"))
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-08T08:00:00Z",
            "2025-01-09T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `interval skips the intervening days`() {
        val result = expand(spec(rrule = "FREQ=DAILY;INTERVAL=2;COUNT=3", anchor = "2025-01-07T08:00:00Z"))
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-09T08:00:00Z",
            "2025-01-11T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `weekly by day expands to the named weekdays`() {
        // The anchor is a Tuesday, which BYDAY=MO,WE,FR does not name. DTSTART is
        // the first instance of the set regardless (RFC 5545 §3.8.5.3), so the
        // Tuesday leads and the pattern takes over from there.
        val result = expand(
            spec(rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=5", anchor = "2025-01-07T08:00:00Z"),
        )
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z", // Tue, the anchor
            "2025-01-08T08:00:00Z", // Wed
            "2025-01-10T08:00:00Z", // Fri
            "2025-01-13T08:00:00Z", // Mon
            "2025-01-15T08:00:00Z", // Wed
        ).inOrder()
    }

    @Test
    fun `monthly by day expands to the nth weekday of the month`() {
        // 2025-01-07 is the first Tuesday, so BYDAY=2TU lands on the 14th; the
        // anchor still leads.
        val result = expand(spec(rrule = "FREQ=MONTHLY;BYDAY=2TU;COUNT=3", anchor = "2025-01-07T08:00:00Z"))
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-14T08:00:00Z",
            "2025-02-11T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `monthly by month day skips months that lack the day`() {
        val result = expand(spec(rrule = "FREQ=MONTHLY;BYMONTHDAY=31;COUNT=3", anchor = "2025-01-31T08:00:00Z"))
        assertThat(result).containsExactly(
            "2025-01-31T08:00:00Z",
            "2025-03-31T07:00:00Z", // February and April have no 31st; March is already CEST
            "2025-05-31T07:00:00Z",
        ).inOrder()
    }

    @Test
    fun `yearly rule repeats on the anniversary`() {
        val result = expand(spec(rrule = "FREQ=YEARLY;COUNT=3", anchor = "2025-01-07T08:00:00Z"))
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2026-01-07T08:00:00Z",
            "2027-01-07T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `yearly rule on a leap day only recurs in leap years`() {
        val result = expand(spec(rrule = "FREQ=YEARLY;COUNT=3", anchor = "2024-02-29T08:00:00Z"))
        assertThat(result).containsExactly(
            "2024-02-29T08:00:00Z",
            "2028-02-29T08:00:00Z",
            "2032-02-29T08:00:00Z",
        ).inOrder()
    }

    // --- limits --------------------------------------------------------------

    @Test
    fun `COUNT limits the series`() {
        val result = expand(spec(rrule = "FREQ=DAILY;COUNT=2", anchor = "2025-01-07T08:00:00Z"))
        assertThat(result).hasSize(2)
    }

    @Test
    fun `UNTIL includes an occurrence falling exactly on it`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY;UNTIL=20250109T080000Z", anchor = "2025-01-07T08:00:00Z"),
        )
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-08T08:00:00Z",
            "2025-01-09T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `a floating UNTIL is read in the series zone`() {
        // RFC 5545 §3.3.10 requires UNTIL in UTC when DTSTART carries a zone, and
        // lib-recur throws outright on the mismatch. Stored rules break the rule
        // anyway, so 09:00 floating has to mean 09:00 in Berlin.
        val result = expand(
            spec(rrule = "FREQ=DAILY;UNTIL=20250109T090000", anchor = "2025-01-07T08:00:00Z"),
        )
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-08T08:00:00Z",
            "2025-01-09T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `a date-valued UNTIL covers the whole of its last day`() {
        // ⚠️ A DATE-valued UNTIL is midnight, and the repair below rebuilt it
        // from hours/minutes/seconds — all zero for a DATE — so a series anchored
        // at 09:00 Berlin lost every occurrence on the final day. RFC 5545
        // §3.3.10 puts the bound at the end of that date.
        val result = expand(
            spec(rrule = "FREQ=DAILY;UNTIL=20250109", anchor = "2025-01-07T08:00:00Z"),
        )
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-08T08:00:00Z",
            "2025-01-09T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `an all-day series with a date-valued UNTIL keeps its last day`() {
        val result = expand(
            spec(
                rrule = "FREQ=DAILY;UNTIL=20250109",
                anchor = "2025-01-07T00:00:00Z",
                isAllDay = true,
                timeZone = null,
            ),
        )
        assertThat(result).hasSize(3)
    }

    @Test
    fun `an unbounded rule stops at the occurrence ceiling`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY", anchor = "2025-01-07T08:00:00Z"),
            window(max = 4),
        )
        assertThat(result).hasSize(4)
        assertThat(result.last()).isEqualTo("2025-01-10T08:00:00Z")
    }

    @Test
    fun `a sub-daily series spends most of the ceiling on occurrences from the pivot on`() {
        // The real shape: an eight-hourly task, a year of window behind now and a
        // 500-occurrence ceiling. Filling the budget from the window start would
        // exhaust it ~166 days before now, so the task would never appear in Today
        // or Upcoming at all.
        val result = expand(
            spec(rrule = "FREQ=HOURLY;INTERVAL=8", anchor = "2024-01-01T00:00:00Z"),
            ExpansionWindow(
                from = at("2024-06-01T00:00:00Z"),
                until = at("2026-06-01T00:00:00Z"),
                maxOccurrences = 500,
                pivot = at("2025-06-01T00:00:00Z"),
            ),
        )
        assertThat(result).hasSize(500)
        // A quarter of the budget looks back, and it keeps the *most recent* of
        // the past — not the oldest, which is what filling from the window start
        // would have kept. (ISO-8601 UTC sorts lexicographically.)
        assertThat(result.count { it < "2025-06-01T00:00:00Z" }).isEqualTo(125)
        assertThat(result.first()).isGreaterThan("2025-04-01T00:00:00Z")
        assertThat(result.last()).isGreaterThan("2025-09-01T00:00:00Z")
    }

    @Test
    fun `the ceiling goes entirely to the future when nothing precedes the pivot`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY", anchor = "2025-01-07T08:00:00Z"),
            ExpansionWindow(
                from = at("2025-01-01T00:00:00Z"),
                until = at("2030-01-01T00:00:00Z"),
                maxOccurrences = 4,
                pivot = at("2025-01-01T00:00:00Z"),
            ),
        )
        assertThat(result).hasSize(4)
        assertThat(result.last()).isEqualTo("2025-01-10T08:00:00Z")
    }

    @Test
    fun `an unbounded rule stops at the window end`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY", anchor = "2025-01-07T08:00:00Z"),
            window(until = "2025-01-10T00:00:00Z"),
        )
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-08T08:00:00Z",
            "2025-01-09T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `the window end is exclusive`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY", anchor = "2025-01-07T08:00:00Z"),
            window(until = "2025-01-09T08:00:00Z"),
        )
        assertThat(result).doesNotContain("2025-01-09T08:00:00Z")
    }

    @Test
    fun `occurrences before the window start are skipped`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY", anchor = "2025-01-07T08:00:00Z"),
            window(from = "2025-06-01T00:00:00Z", until = "2025-06-04T00:00:00Z"),
        )
        assertThat(result).containsExactly(
            "2025-06-01T07:00:00Z",
            "2025-06-02T07:00:00Z",
            "2025-06-03T07:00:00Z",
        ).inOrder()
    }

    // --- DST and all-day -----------------------------------------------------

    @Test
    fun `a daily series keeps its local time across a DST boundary`() {
        // Europe/Berlin springs forward on 2025-03-30, so 09:00 local moves from
        // 08:00Z to 07:00Z while the wall-clock time the user set stays put.
        val result = expand(spec(rrule = "FREQ=DAILY;COUNT=4", anchor = "2025-03-28T08:00:00Z"))
        assertThat(result).containsExactly(
            "2025-03-28T08:00:00Z",
            "2025-03-29T08:00:00Z",
            "2025-03-30T07:00:00Z",
            "2025-03-31T07:00:00Z",
        ).inOrder()
    }

    @Test
    fun `an all-day series pins every occurrence to UTC midnight`() {
        // Date-anchored, matching TaskWriteMapper's forAllDay: the stored zone and
        // the device zone are both irrelevant, and no DST shift reaches it.
        val result = expand(
            spec(
                rrule = "FREQ=DAILY;COUNT=3",
                anchor = "2025-03-29T22:45:00Z",
                isAllDay = true,
                timeZone = berlin,
            ),
        )
        assertThat(result).containsExactly(
            "2025-03-29T00:00:00Z",
            "2025-03-30T00:00:00Z",
            "2025-03-31T00:00:00Z",
        ).inOrder()
    }

    @Test
    fun `an all-day weekly series stays on the same weekday`() {
        val result = expand(
            spec(rrule = "FREQ=WEEKLY;COUNT=3", anchor = "2025-01-15T00:00:00Z", isAllDay = true, timeZone = null),
        )
        assertThat(result).containsExactly(
            "2025-01-15T00:00:00Z",
            "2025-01-22T00:00:00Z",
            "2025-01-29T00:00:00Z",
        ).inOrder()
    }

    @Test
    fun `a series with no zone expands in the floating zone`() {
        val spec = spec(rrule = "FREQ=DAILY;COUNT=2", anchor = "2025-03-08T14:00:00Z", timeZone = null)
        // 09:00 in New York, over the 2025-03-09 US DST switch.
        assertThat(expand(spec, floatingZone = newYork)).containsExactly(
            "2025-03-08T14:00:00Z",
            "2025-03-09T13:00:00Z",
        ).inOrder()
    }

    // --- RDATE / EXDATE ------------------------------------------------------

    @Test
    fun `RDATE adds occurrences the rule does not produce`() {
        val result = expand(
            spec(
                rrule = "FREQ=DAILY;COUNT=2",
                rdate = "20250115T140000",
                anchor = "2025-01-07T08:00:00Z",
            ),
        )
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-08T08:00:00Z",
            "2025-01-15T13:00:00Z", // 14:00 Berlin
        ).inOrder()
    }

    @Test
    fun `an RDATE before the anchor is part of the set`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY;COUNT=2", rdate = "20250101T090000", anchor = "2025-01-07T08:00:00Z"),
        )
        assertThat(result.first()).isEqualTo("2025-01-01T08:00:00Z")
    }

    @Test
    fun `an RDATE repeating a rule instance is not emitted twice`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY;COUNT=3", rdate = "20250108T090000", anchor = "2025-01-07T08:00:00Z"),
        )
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-08T08:00:00Z",
            "2025-01-09T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `multiple RDATEs are comma-separated`() {
        val result = expand(
            spec(rdate = "20250110T090000,20250112T090000", anchor = "2025-01-07T08:00:00Z"),
        )
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-10T08:00:00Z",
            "2025-01-12T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `EXDATE removes an occurrence`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY;COUNT=3", exdate = "20250108T090000", anchor = "2025-01-07T08:00:00Z"),
        )
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-09T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `EXDATE can remove the anchor itself`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY;COUNT=3", exdate = "20250107T090000", anchor = "2025-01-07T08:00:00Z"),
        )
        assertThat(result).containsExactly(
            "2025-01-08T08:00:00Z",
            "2025-01-09T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `a UTC EXDATE matches a zoned occurrence at the same instant`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY;COUNT=3", exdate = "20250108T080000Z", anchor = "2025-01-07T08:00:00Z"),
        )
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-09T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `an all-day EXDATE removes the matching date`() {
        val result = expand(
            spec(
                rrule = "FREQ=DAILY;COUNT=3",
                exdate = "20250116",
                anchor = "2025-01-15T00:00:00Z",
                isAllDay = true,
            ),
        )
        assertThat(result).containsExactly(
            "2025-01-15T00:00:00Z",
            "2025-01-17T00:00:00Z",
        ).inOrder()
    }

    // --- degenerate input ----------------------------------------------------

    @Test
    fun `a spec with no rule expands to just its anchor`() {
        assertThat(expand(spec(anchor = "2025-01-07T08:00:00Z")))
            .containsExactly("2025-01-07T08:00:00Z")
    }

    @Test
    fun `a malformed rule degrades to the anchor instead of throwing`() {
        assertThat(expand(spec(rrule = "FREQ=NONSENSE", anchor = "2025-01-07T08:00:00Z")))
            .containsExactly("2025-01-07T08:00:00Z")
    }

    @Test
    fun `a malformed RDATE is dropped and the rule still expands`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY;COUNT=2", rdate = "not-a-date", anchor = "2025-01-07T08:00:00Z"),
        )
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-08T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `an unknown zone id falls back to the floating zone`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY;COUNT=2", anchor = "2025-03-08T14:00:00Z", timeZone = "Mars/Olympus"),
            floatingZone = newYork,
        )
        assertThat(result).containsExactly(
            "2025-03-08T14:00:00Z",
            "2025-03-09T13:00:00Z",
        ).inOrder()
    }

    @Test
    fun `a sub-second anchor does not double the first occurrence`() {
        // RFC 5545 DATE-TIME has second precision, but a task created from
        // Clock.now() carries millis. Left un-floored, lib-recur emits the raw
        // anchor *and* its truncated self, so the series starts twice.
        val result = expand(spec(rrule = "FREQ=DAILY;COUNT=3", anchor = "2025-01-07T08:00:00.081Z"))
        assertThat(result).containsExactly(
            "2025-01-07T08:00:00Z",
            "2025-01-08T08:00:00Z",
            "2025-01-09T08:00:00Z",
        ).inOrder()
    }

    @Test
    fun `a window that ends before the anchor yields nothing`() {
        val result = expand(
            spec(rrule = "FREQ=DAILY", anchor = "2025-01-07T08:00:00Z"),
            window(until = "2024-01-01T00:00:00Z"),
        )
        assertThat(result).isEmpty()
    }
}

# VTODO fixture corpus

The mapper's **specification**, not its regression net. Every file here is a
shape that was found in the wild and that a plausible implementation gets wrong.
A clean, server-generated VTODO catches nothing and is deliberately absent.

`RoundTripTest` asserts every fixture survives `parse → map → write → serialise`
with no property lost, compared as a canonical multiset modulo an allowlist
(`PRODID`, `DTSTAMP`, `LAST-MODIFIED`, `SEQUENCE`,
VTIMEZONE bodies, fold positions, parameter quoting).

| File | What it is for |
|---|---|
| `nextcloud-shared-confidential.ics` | A `CLASS:CONFIDENTIAL` task as Nextcloud rewrites it on GET from a **shared** calendar — `DUE`, `STATUS`, `COMPLETED`, `PERCENT-COMPLETE`, `PRIORITY` and `RELATED-TO` already stripped, ETag left untouched. Re-PUTting this destroys the owner's task |
| `recurring-master-overrides.ics` | Master plus two `RECURRENCE-ID` overrides in one resource, sharing a UID |
| `valarm-unknown-nested.ics` | Unknown properties **inside** a `VALARM` — the case a flat property-per-row model cannot represent |
| `unknown-component.ics` | An entirely unknown sub-component |
| `unknown-tzid.ics` | A `TZID` no device tzdb knows, with its own `VTIMEZONE` |
| `uid-special-chars.ics` | A UID containing `/` and `@` — the href sanitiser must not assume it is filename-safe |
| `rrule-due-no-dtstart.ics` | `RRULE` + `DUE` and no `DTSTART`: no well-defined `RECURRENCE-ID`, undefined in RFC 5545, ubiquitous in the wild |
| `moz-alarm-props.ics` | `X-MOZ-LASTACK` / `X-MOZ-SNOOZE-TIME`. Dropping them causes documented Thunderbird alarm storms |
| `apple-sort-order.ics` | `X-APPLE-SORT-ORDER` — preserve, never interpret |
| `floating-time.ics` | `DTSTART` with neither `Z` nor `TZID`. Readable, not reproducible: it must round-trip from the residue, not be rewritten as a guess |
| `completion-model-a.ics` | Completion as a `RECURRENCE-ID` override, master stays open — jtx Board, Thunderbird. **The model we write** |
| `completion-model-b.ics` | Master's `DUE` advanced in place, completion cleared — tasks.org, Evolution, Nextcloud in practice |
| `completion-model-c.ics` | `STATUS:COMPLETED` on the master, killing the series — Nextcloud Tasks ≤ 0.17. **Always a bug**; we read it, we never write it |
| `completion-model-d.ics` | The completed occurrence detached as a new task with a new UID — OpenTasks |
| `quoted-param-colon.ics` | A quoted parameter value containing a colon and a comma. The value separator is the first colon *outside* quotes |
| `mixed-value-types.ics` | `DTSTART;VALUE=DATE` with a timed `DUE`. One `is_all_day` flag cannot author both, so the odd one out must stay in the residue rather than be flattened to a date |
| `malformed-scalars.ics` | `PRIORITY:11`, `PERCENT-COMPLETE:150`, `SEQUENCE:x`, `STATUS:X-DEFERRED`, `CLASS:X-INTERNAL` — values no spec allows. Clamping or defaulting any of them is a silent rewrite of somebody's data |
| `two-timezones.ics` | `DTSTART` and `DUE` in different zones. One `timezone` column cannot author both |
| `folded-utf8.ics` | A line folded mid-emoji-adjacent, to prove folding counts octets and splits on character boundaries |
| `categories-shapes.ics` | One `CATEGORIES` line with an escaped comma inside a value — the one shape the column claims, so it is re-authored and must come out identical |
| `categories-split.ics` | One line per tag, a `LANGUAGE` parameter and a space after a comma. Read for display, but none of it is ours to rewrite |

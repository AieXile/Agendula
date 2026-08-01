<div align="center">

<h1>Agendula</h1>

<p><strong>A modern Material 3 Expressive task app for Android.</strong><br>
Reads, writes, and reminds — on top of an existing tasks provider, with no own
sync stack.</p>

<a href="https://codeberg.org/jlmakiola/agendula/actions"><img src="https://codeberg.org/jlmakiola/agendula/actions/workflows/ci.yaml/badge.svg?branch=main" alt="CI"></a>
<img src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white" alt="Android 10+">
<img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin + Compose">
<img src="https://img.shields.io/badge/Material%203-Expressive-4285F4" alt="Material 3 Expressive">
<a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-green" alt="MIT License"></a>

</div>

Agendula is the task-list sibling to [Calendula](https://codeberg.org/jlmakiola/calendula).
Where Calendula is a pure front-end over Android's `CalendarContract`, Agendula is
a pure front-end over the **OpenTasks `TaskContract` provider** — the store that
DAVx5 (and SmoothSync, DecSync, …) syncs your CalDAV `VTODO` tasks into. No own
database, no reinvented sync.

The name rhymes with its sibling on purpose: **Agendula** is *agenda* — Latin for
“things to be done” — given Calendula's `-ula` ending. Calendula keeps your days;
Agendula keeps your to-dos. (A Calendula flower head is botanically a cluster of
many small *florets* — so the two apps are florets of one bloom.)

> **Status: data layer done, UI in progress.** The full non-visual stack over
> the `TaskContract` provider — provider resolution, live-updating reads,
> writes, smart-list filtering, and a self-scheduled reminder engine — is built
> and unit-tested. The Material 3 Expressive screens are now being built on top,
> one at a time. See [`docs/ROADMAP.md`](docs/ROADMAP.md) for status,
> [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for how it's built, and
> [`docs/PLAN.md`](docs/PLAN.md) for the A-now-B-later design rationale.

## Sync sources (by design)

Agendula works with anything that writes to the tasks provider — **DAVx5**
(CalDAV), **SmoothSync**, **CalDAV-Sync**, **DecSync CC**, or any Android sync
adapter — because it builds on the provider, not on any one sync app. Google
Tasks / Microsoft To Do are out of scope by design (proprietary; they would mean
owning a sync stack). Open standards — CalDAV / iCalendar / DecSync — are the lane.

## Translations

Agendula ships in English so far, and would like not to. Translations are
managed on a self-hosted **Weblate**, and partial ones are fine — an
untranslated string simply falls back to English.

**→ [Help translate Agendula](https://weblate.dev.jeanlucmakiola.de/engage/agendula/)**

No coding needed: register on the Weblate server, pick (or request) a language,
and translate the strings in your browser. You can also reach this link in the
app from the top of **Settings → App language**.

## License

MIT — see [LICENSE](LICENSE).

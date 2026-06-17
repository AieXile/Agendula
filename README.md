<div align="center">

<h1>Floret</h1>

<p><strong>A modern Material 3 Expressive task app for Android.</strong><br>
Reads, writes, and reminds — on top of an existing tasks provider, with no own
sync stack.</p>

<img src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white" alt="Android 10+">
<img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin + Compose">
<img src="https://img.shields.io/badge/Material%203-Expressive-4285F4" alt="Material 3 Expressive">
<a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-green" alt="MIT License"></a>

</div>

Floret is the task-list sibling to [Calendula](https://gitea.jeanlucmakiola.de/makiolaj/calendula).
Where Calendula is a pure front-end over Android's `CalendarContract`, Floret is
a pure front-end over the **OpenTasks `TaskContract` provider** — the store that
DAVx5 (and SmoothSync, DecSync, …) syncs your CalDAV `VTODO` tasks into. No own
database, no reinvented sync.

A Calendula flower head is botanically made of many small *florets* — the
individual items that make up the bloom. Floret is those items: your tasks.

> **Status: M0 — skeleton.** Builds and shows a themed placeholder. The
> `TaskContract` data layer, task screens, and reminder engine are the next
> milestones. See [`docs/PLAN.md`](docs/PLAN.md) for the full roadmap and the
> A-now-B-later architecture (front-end first; bundle the provider later).

## Sync sources (by design)

Floret works with anything that writes to the tasks provider — **DAVx5**
(CalDAV), **SmoothSync**, **CalDAV-Sync**, **DecSync CC**, or any Android sync
adapter — because it builds on the provider, not on any one sync app. Google
Tasks / Microsoft To Do are out of scope by design (proprietary; they would mean
owning a sync stack). Open standards — CalDAV / iCalendar / DecSync — are the lane.

## License

MIT — see [LICENSE](LICENSE).

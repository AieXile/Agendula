# Floret — documentation

Floret is a Material 3 Expressive **task** app for Android: a pure front-end over
the OpenTasks `TaskContract` provider (synced by DAVx5 / SmoothSync / DecSync
over CalDAV), with no own database or sync stack. Sibling to
[Calendula](https://gitea.jeanlucmakiola.de/makiolaj/calendula). See the
top-level [`../README.md`](../README.md) for the project pitch.

## Index

| Doc | What it covers |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | How Floret is built **today** — layers, the data seam, provider resolution, the reminder engine, DI, build/tooling, manifest. Start here to work on the code. |
| [`ROADMAP.md`](ROADMAP.md) | **Status** and what's next — milestones (M0–M6 + Posture B), what's done, open decisions, how to build/verify. |
| [`PLAN.md`](PLAN.md) | The original implementation plan and **design rationale** — the A-now-B-later thesis, what transfers from Calendula, the locked decisions. The "why". |
| [`RELEASING.md`](RELEASING.md) | How to cut a release — the git-tag-as-source-of-truth flow, CI jobs, F-Droid repo, required secrets. |

Also: [`../CHANGELOG.md`](../CHANGELOG.md) (Keep a Changelog format; tag sections
feed the release notes).

## How the docs relate

- **PLAN** is the design decisions (mostly stable; the "why").
- **ARCHITECTURE** is the current shape of the code (kept in sync with the
  source as it grows).
- **ROADMAP** is the moving status layer (update as milestones land).
- **RELEASING** is the operational runbook.

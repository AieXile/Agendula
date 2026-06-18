# Floret — roadmap

Where the project is and where it's going. This is the **status** view; the
design rationale behind each milestone lives in [`PLAN.md`](PLAN.md), and how the
pieces fit together is in [`ARCHITECTURE.md`](ARCHITECTURE.md).

Status legend: ✅ done · 🚧 in progress · ⬜ not started

---

## Current state (one line)

The full non-visual stack ("backoffice") over the OpenTasks `TaskContract`
provider is **done and unit-tested**; the Material 3 Expressive UI is being
built screen by screen on top of it. App builds, gates on provider/permission,
and navigates lists → task list → detail / edit through base screen scaffolds.

---

## Milestones

### ✅ M0 — Skeleton
Project scaffolding copied from Calendula (Gradle, version catalog, Hilt,
Material 3 Expressive theme, Gitea CI/release workflows, F-Droid metadata),
reseeded to a warm-mauve fallback palette. Builds, shows a themed placeholder.

### ✅ M1 — Read path + logic (the "backoffice")
The complete non-visual stack:
- Vendored `TaskContract` subset, `ProviderResolver` (runtime authority
  detection), `ColumnReader`, mappers, `AndroidTasksDataSource` (Instances
  query + `ContentObserver`), and `TasksRepository` exposing live Flows of
  lists / tasks / detail.
- Domain models, smart-list filtering (Today / Upcoming / Overdue / No-date /
  All / Completed), sorting, form validation, subtasks via `parentId`.
- Self-scheduled due-reminder engine (AlarmManager + boot / provider-change
  re-sync), notifications, DataStore prefs.
- Render-only ViewModels + UiState for **every** screen, so the UI is build-only
  from here.
- JVM unit tests across mappers, filtering, sorting, form, value mapping, and
  day windows.

### 🚧 M2 — Screens: lists, task list, complete & CRUD
Replace the functional scaffold with the real Material 3 Expressive UI, screen
by screen, against the already-built ViewModels.
- ✅ Provider/permission onboarding gate (`RootScreen`).
- ✅ Lists overview (`ListsScreen`) — smart lists + user lists grouped by account.
- ✅ Navigation host wiring (`FloretNavHost` + `Dest` route table: lists → task
  list → detail / edit, each binding its M1 ViewModel from route args).
- 🚧 Task list screen — base scaffold lands (checkbox rows, toggle-complete, open
  / new-task nav). Remaining: swipe-to-complete / -delete, inline add field,
  section headers for smart lists.
- 🚧 Detail + edit screens — base scaffolds land (detail shows title /
  description / subtasks with edit+delete; edit has title / description / save).
  Remaining: full CRUD fields and local-list creation wired to the UI.

### ⬜ M3 — Detail / edit polish
Task detail and edit screens: due/start date-time pickers, priority,
percent-complete, conflict-safe saves (re-check before overwrite), smart-list
section presentation.

### ⬜ M4 — Subtasks (UI)
`RELATED-TO` hierarchy in the UI: indentation, create subtask, reparent. The
data layer already reads `parentId`; this is the trickiest UI piece.

### ⬜ M5 — Reminders onboarding & polish
The engine exists (M1). Remaining: the `POST_NOTIFICATIONS` + exact-alarm
onboarding flow, per-task / default reminder-offset settings UI, and
end-to-end verification on device.

### ⬜ M6 — Settings, widget, i18n, release
Settings screen (theme / dynamic-color / language, default list, default
reminder), Glance task-list widget, translations, finalize F-Droid metadata,
confirm CI release flow.

### ⬜ Posture B (separate track, later)
Add a `:provider` module bundling the Apache-2.0 `opentasks-provider`;
`ProviderResolver` defaults to our own `org.dmfs.tasks`; add sync-adapter
permissions; ship self-contained. UI / repository / domain untouched — see
[`ARCHITECTURE.md`](ARCHITECTURE.md) §7.

---

## Open decisions / to verify

These carry over from [`PLAN.md`](PLAN.md) §9; resolved ones are struck through.

1. ~~**Name** — `Floret`~~ confirmed (appId `de.jeanlucmakiola.floret`).
2. ~~**tasks.org provider authority**~~ verified on device:
   `org.tasks.opentasks` + `org.tasks.permission.*`.
3. **jtx Board** — support its richer contract later, or stay OpenTasks-only?
   (Not in the candidate list today.)
4. **Posture B authority choice** — bundling `org.dmfs.tasks` makes Floret a
   *replacement* for OpenTasks (one authority owner per device). Intended, but a
   conscious choice.
5. **Recurring tasks** — read as occurrences today (`isRecurring` flag exists);
   recurrence-aware editing is out of scope for v1.

---

## How to contribute / verify

- Build: `./gradlew :app:assembleDebug`
- Unit tests: `./gradlew :app:testDebugUnitTest`
- Run on a device/emulator that has **OpenTasks** or **tasks.org** installed (and
  ideally DAVx5 syncing a CalDAV task list) so the read/write paths have real
  data. Debug builds use `DemoSeeder` for sample data when no provider data is
  present.

# Floret — roadmap

Where the project is and where it's going. This is the **status** view; the
design rationale behind each milestone lives in [`PLAN.md`](PLAN.md), and how the
pieces fit together is in [`ARCHITECTURE.md`](ARCHITECTURE.md).

Status legend: ✅ done · 🚧 in progress · ⬜ not started

---

## Current state (one line)

The full non-visual stack ("backoffice") over the OpenTasks `TaskContract`
provider is **done and unit-tested**, and the Material 3 Expressive UI is built
through **M4**: lists → task list (swipe gestures, inline add, smart-list section
headers) → detail / edit with full CRUD, date-time pickers, priority,
percent-complete, conflict-safe saves, per-task reminders, and subtask
create + reparent. Remaining work is M5 (notification / exact-alarm onboarding,
default reminder UI) and M6 (Settings screen, Glance widget, i18n, release).

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

### ✅ M2 — Screens: lists, task list, complete & CRUD
The real Material 3 Expressive UI, built screen by screen against the M1
ViewModels.
- ✅ Provider/permission onboarding gate (`RootScreen`).
- ✅ Lists overview (`ListsScreen`) — smart lists + user lists grouped by account.
- ✅ Navigation host wiring (`FloretNavHost` + `Dest` route table: lists → task
  list → detail / edit, each binding its M1 ViewModel from route args).
- ✅ Task list screen — checkbox rows + toggle-complete, swipe-to-complete /
  -delete (`SwipeToDismissBox`), inline add field (`InlineAdd` → `quickAdd`),
  smart-list section headers (`TaskSections`).
- ✅ Detail + edit screens — detail shows title / description / subtasks with
  edit+delete; edit has the full CRUD form (title, description, save) wired to a
  list picker.

### ✅ M3 — Detail / edit polish
- ✅ Due / start date-time pickers (`DateTimePickerFlow` in `TaskEditScreen`).
- ✅ Priority — coloured by level: green / amber / red pastels (`priorityFill`;
  M3 has no priority role, only `error`). A shared `ui/common/PriorityChip` on the
  list and detail screens, and the edit form's M3 segmented selector tints its
  active segment in the chosen level's hue.
- ✅ Smart-list section presentation (`TaskSections` headers on the task list).
- ✅ Percent-complete field — optional "Progress" slider (5% detents) on the edit
  form; writes `Tasks.PERCENT_COMPLETE` (clamped 0–100, status left to the
  complete toggle).
- ✅ Conflict-safe saves — `updateTask` re-checks `last_modified` against the
  value captured when the form loaded and throws `TaskConflictException`; the
  editor offers overwrite-or-cancel instead of clobbering an external change.

### ✅ M4 — Subtasks (UI)
`RELATED-TO` hierarchy in the UI. The data layer already reads `parentId`.
- ✅ Create subtask (`AddSubtaskField` → `TaskDetailViewModel.addSubtask` sets
  `parentId`); subtasks render as a grouped section in the detail screen. Tapping
  a subtask opens its own detail (a new `TaskDetail` entry, so it can have
  children too), and the subtask's detail shows a tappable "Part of …" parent
  card so it never reads as a stray standalone task.
- ✅ Inline expansion on the task list — a parent row has a dedicated expand
  button (trailing chevron, separate from the count chip). Each section flattens
  into one grouped run (parents + their expanded children) and corners are chosen
  per-edge (`cornerPosition`): top-level tasks keep their own run, an expanded
  parent opens its bottom to its children, and the child group rounds off on its
  last segment while the next top-level task stays mid-run. Children are
  full-width, set a step down in tone (`surfaceContainer` vs the parents'
  `surfaceContainerHigh`) and with no colour bar (checkbox stays aligned). An expanded group ends with an inline "add a subtask"
  row (always on for now; an opt-out toggle lands with the M6 settings screen).
  Offered only where the list holds all the children (a real list; smart lists
  that omit off-day children stay collapsed). `TaskDetail.parent` carries the
  parent for the detail card.
- ✅ Reparent — a full-width, searchable "Parent task" sheet on the edit form
  groups active candidates by due-date section (Overdue / Today / Upcoming / No
  date) and files a task under any top-level task in its list (or "None" to
  promote it); switching list clears the now-invalid parent. Candidates stay
  active + top-level to keep nesting one level deep, matching the detail screen.

### 🚧 M5 — Reminders onboarding & polish
The engine exists (M1: `ReminderScheduler` + boot / provider-change re-sync,
`DueReminderReceiver`, `TaskNotifier`).
- ✅ Per-task reminder-offset UI (`ReminderPickerDialog` → `TaskForm`
  `reminderMinutesBeforeDue`).
- ⬜ `POST_NOTIFICATIONS` + exact-alarm onboarding flow — currently only graceful
  runtime checks (`TaskNotifier.canPost`, `canScheduleExactAlarms`), no
  user-facing gate.
- ⬜ Default reminder-offset settings UI — `SettingsPrefs`/`SettingsViewModel`
  back it, but no Composable exposes it.
- ⬜ End-to-end verification on device.

### 🚧 M6 — Settings, widget, i18n, release
- ✅ F-Droid metadata scaffolded (`fdroid-metadata/`).
- ⬜ Settings screen — `SettingsViewModel` exists but no `SettingsScreen`
  Composable and it is not in the nav graph (only used by `MainActivity` for
  app-level theming). Needs theme / dynamic-color / language, default list,
  default reminder, and the opt-out toggle for the task list's inline
  add-a-subtask row (on by default since M4).
- ⬜ Glance task-list widget — deps present in `build.gradle.kts`, zero impl.
- ⬜ Translations — only `res/values/` (English); no `values-XX`.
- ⬜ Finalize F-Droid metadata, confirm CI release flow.

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

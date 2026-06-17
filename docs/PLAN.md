# Floret — implementation plan

> A modern Material 3 Expressive **task** app for Android. Reads, writes, and
> reminds — on top of an existing tasks provider (synced by DAVx5 / SmoothSync /
> DecSync over CalDAV), with no own sync stack.
>
> Sibling to **Calendula**. Calendula is a calendar over `CalendarContract`;
> Floret is a to-do list over the **OpenTasks `TaskContract` provider**. A
> Calendula flower head is made of many small *florets* — the individual items
> that make up the bloom.

Working identifiers (placeholder, easily renamed):
`applicationId = de.jeanlucmakiola.floret`, app name **Floret**.

---

## 0. The thesis this app embodies

A nice M3-Expressive front end over open backends, no reinvented storage or
sync. Calendula proved the pattern against the OS calendar provider. Floret
applies it to tasks. The crucial difference: **there is no OS tasks provider**,
so we depend on a tasks *provider app* being present — exactly as Calendula
depends on a sync app like DAVx5 for CalDAV.

### Posture A now, Posture B long-term (locked decision)

- **A (this plan):** pure front-end over whatever tasks provider is installed
  (OpenTasks / tasks.org / jtx). Fast to ship; requires a provider app present.
- **B (later):** bundle the Apache-2.0 `opentasks-provider` so the app is
  self-contained (owns the `org.dmfs.tasks` authority + `org.dmfs.permission.*`,
  DAVx5 syncs directly into it). Bundling the provider bundles **storage, not
  sync** — external CalDAV engines still feed it, which keeps us true to the
  thesis.

**The one rule that makes B additive instead of a rewrite:** the entire app
talks to a `TasksRepository`; only *one* class (`OpenTasksDataSource`) knows
about a `ContentResolver`, `TaskContract`, or an authority string, and it
resolves its authority at **runtime** via `ProviderResolver`. In A the resolver
finds the installed provider; in B it finds our own bundled one. Repo + UI +
domain never change. **Never let `TaskContract` column names or the authority
string leak above the data layer.**

---

## 1. What transfers from Calendula

Calendula's layering is the template. Lift these **verbatim or near-verbatim**:

| Area | From Calendula | Change for Floret |
|---|---|---|
| Gradle setup | `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, wrapper, `key.properties` flow, versionCode-from-tag CI | namespace/appId only |
| Build config | AGP 9.2.1, Kotlin 2.3.21, KSP, Hilt 2.59.2, compileSdk 37 / minSdk 29 / targetSdk 36, Java 17 | identical |
| Theme | `ui/theme/Theme.kt` (`MaterialExpressiveTheme`, `MotionScheme.standard()`, dynamic color + hand-tuned fallback), `Type.kt`, `Color.kt` | reseed fallback palette |
| DI shape | `data/di/DataModule.kt` (`DataBindModule` + `DataProvideModule`), `@IoDispatcher` qualifier, DataStore wiring | rename store |
| Data seam pattern | `CalendarRepository` (Flow API) + `CalendarRepositoryImpl` wrapping a `CalendarDataSource` interface + `AndroidCalendarDataSource` (Cursor/ContentObserver), `Projections`, `ColumnReader`, mappers | retarget to `TaskContract` |
| Reactive flows | `ContentObserver` → `callbackFlow` bridge in the data source | observe tasks URI |
| Notifications | `ReminderNotifier` (channel, POST_NOTIFICATIONS gate, dedupe by tag) | reuse almost as-is |
| Prefs | `data/prefs/SettingsPrefs`, DataStore | reuse |
| Settings/onboarding/permission UI | `ui/settings`, `ui/permission` (`OnboardingScaffold`, `PermissionScreen`) | adapt copy + permissions |
| Widgets | Glance `widget/` scaffolding (receiver + `PROVIDER_CHANGED` refresh) | task-list widget |
| Common UI | `ui/common/*` (GroupedList, InlineTextField, OptionCard, ColorSwatchRow, FailureView, transitions) | reuse |
| Test stack | JUnit5 + Truth + Turbine + coroutines-test; data source as a JVM-testable seam | reuse |

**Net: ~70–80% of the scaffolding is a copy.** The genuinely new code is the
`TaskContract` data layer, the task screens, and a **self-scheduled reminder
engine** (see §6 — the one place Calendula's pattern does *not* carry over).

### What does NOT exist here (why Floret is simpler than Calendula)

No month/week/day grid rendering. No recurrence-scoped writes ("this & following"
vs "whole series"). No timezone/all-day gymnastics. Tasks are a flat-or-lightly-
nested list with a due date and a checkbox.

---

## 2. Module & package layout

Single `:app` module for A (mirrors Calendula). Package root
`de.jeanlucmakiola.floret`.

```
domain/
  Models.kt            TaskList, Task, TaskStatus, Priority, SubtaskRelation
  TaskForm.kt          validated create/edit form (mirrors EventForm)
  Filters.kt           smart lists: Today, Upcoming, Overdue, Completed, All
data/
  di/                  Qualifiers.kt, DataModule.kt  (copy + retarget)
  tasks/
    TasksContract.kt   vendored subset of OpenTasks TaskContract (Apache-2.0)
    ProviderResolver.kt  detect installed provider authority + permissions  ← the A/B seam
    TaskProjections.kt   column lists
    ColumnReader.kt      (copy from Calendula)
    TaskMapper.kt        cursor row -> domain
    TaskWriteMapper.kt   form -> ContentValues
    TasksDataSource.kt       interface (JVM-testable seam)
    OpenTasksDataSource.kt   ContentResolver/ContentObserver impl
    TasksRepository.kt       Flow API (interface)
    TasksRepositoryImpl.kt   wraps the data source
  reminders/
    DueReminderScheduler.kt  AlarmManager scheduling (NEW — see §6)
    DueReminderReceiver.kt   alarm fires -> post notification
    BootRescheduleReceiver.kt + provider-change reschedule
    TaskNotifier.kt          (copy ReminderNotifier, retarget)
  prefs/               SettingsPrefs (copy)
ui/
  theme/               (copy)
  common/              (copy relevant pieces)
  lists/               ListsScreen + ViewModel + UiState  (overview of lists/accounts)
  tasklist/            TaskListScreen (one list or a smart list) + VM + UiState
  detail/              TaskDetailScreen + VM + UiState
  edit/                TaskEditScreen + VM + UiState
  settings/            (copy + adapt)
  permission/          provider-presence + permission + POST_NOTIFICATIONS onboarding
  FloretHost.kt        nav host  (mirrors CalendarHost)
  RootScreen.kt
widget/                Glance task widget (later milestone)
FloretApp.kt, MainActivity.kt
```

---

## 3. The data layer (the heart)

### 3.1 Provider targeting — `ProviderResolver`

Known providers, in preference order, each = (authority, read perm, write perm):

| Provider | Authority | Permissions | Contract |
|---|---|---|---|
| OpenTasks | `org.dmfs.tasks` | `org.dmfs.permission.READ_TASKS` / `WRITE_TASKS` | OpenTasks TaskContract |
| tasks.org | `org.tasks.opentasks` *(verify on device)* | `org.tasks.permission.READ_TASKS` / `WRITE_TASKS` | OpenTasks-compatible |
| jtx Board | `at.techbee.jtx` | `at.techbee.jtx.permission.READ` / `WRITE` | jtx (richer, different) |

`ProviderResolver.detect()`:
1. `PackageManager.resolveContentProvider(authority, 0)` for each known authority.
2. Return the first present as the active `TaskProvider(authority, readPerm, writePerm)`.
3. None present → `null` → drives the "install a tasks provider" onboarding (§5).

**v1 scope:** fully support the **OpenTasks contract** (covers OpenTasks +
tasks.org's compatible provider). jtx is detected but treated as "supported
later" (its contract differs). For B, the resolver simply also finds our bundled
`org.dmfs.tasks`.

> Verify the exact tasks.org provider authority on a real device before relying
> on it — docs are inconsistent; OpenTasks (`org.dmfs.tasks`) is the certain one.

### 3.2 `TasksContract.kt`

Vendor the subset we use from the Apache-2.0 OpenTasks `TaskContract` (don't take
a runtime dep on OpenTasks). Authority is injected, not hardcoded. Tables/columns:

- **TaskLists**: `_ID`, `LIST_NAME`, `LIST_COLOR`, `ACCOUNT_NAME`, `ACCOUNT_TYPE`,
  `SYNC_ENABLED`, `VISIBLE`, `OWNER`. (Account fields → group lists by account in
  the UI, like Calendula groups calendars.)
- **Tasks** (the denormalized instances view): `_ID`, `LIST_ID`, `TITLE`,
  `DESCRIPTION`, `DTSTART`, `DUE`, `IS_ALLDAY`, `TZ`, `STATUS`, `PRIORITY`,
  `PERCENT_COMPLETE`, `COMPLETED`, `RRULE`, `LIST_COLOR`, `ACCOUNT_*`.
- **Properties / Relation** (`Tasks.Properties`, mimetype Relation): subtasks via
  `RELATED-TO` (`RELATION_TYPE_PARENT`). This is how DAVx5 carries the hierarchy.

### 3.3 Repository API

```kotlin
interface TasksRepository {
    fun taskLists(): Flow<List<TaskList>>
    fun tasks(filter: TaskFilter): Flow<List<Task>>      // list-id or smart list
    suspend fun taskDetail(taskId: Long): TaskDetail
    suspend fun createTask(form: TaskForm): Long
    suspend fun updateTask(taskId: Long, original: TaskForm, updated: TaskForm)
    suspend fun setCompleted(taskId: Long, completed: Boolean)   // the core gesture
    suspend fun deleteTask(taskId: Long)
    suspend fun createLocalList(name: String, color: Int): Long  // device-only list we own
    // subtasks: createSubtask(parentId, form) / reparent via RELATED-TO
}
```

Writes go through the **sync-adapter URI form** where the provider requires it
(local/unsynced lists), mirroring Calendula's `createLocalCalendar`. Completion =
set `STATUS=COMPLETED`, `PERCENT_COMPLETE=100`, `COMPLETED=now`; DAVx5 syncs it
back out as a normal VTODO status change.

### 3.4 Reactive flows

`OpenTasksDataSource` registers a `ContentObserver` on the active authority's
Tasks/TaskLists URIs and emits via `callbackFlow` — identical mechanism to
Calendula, so external sync (DAVx5 pulling new tasks) updates the UI live, and
multiple sync sources coexist in one list.

---

## 4. Screens (Compose, M3 Expressive)

1. **Lists overview** (`ui/lists`) — smart lists at top (Today, Upcoming,
   Overdue, All), then user lists grouped by account (reuse `GroupedList`).
   Per-list color dot + open/undone count.
2. **Task list** (`ui/tasklist`) — the workhorse. Checkbox rows, swipe-to-
   complete + swipe-to-delete, inline "add task" field (reuse `InlineTextField`),
   subtask indentation, sort (due/priority/manual), section headers for smart
   lists (Overdue / Today / Later). FAB to add.
3. **Task detail** (`ui/detail`) — title, notes, due/start, priority,
   percent-complete, subtasks, source list/account, completed timestamp.
4. **Task edit** (`ui/edit`) — title, notes, list picker, due/start date-time
   (reuse Calendula date/time pickers), priority, reminder offset, subtasks.
   "Conflict-safe save" like Calendula (re-check before overwrite).
5. **Settings** (`ui/settings`) — theme/dynamic-color/language (copy), default
   list, default reminder offset, "tasks app" info + manage button.
6. **Onboarding / permission** (`ui/permission`) — see §5.

Material 3 Expressive throughout: `MaterialExpressiveTheme`,
`MotionScheme.standard()`, expressive checkbox/FAB/swipe motion. Follow the
`material-3` skill for component choices (M3 `ListItem` for rows, etc.).

---

## 5. Onboarding & permissions

Three gates, in order:

1. **Provider present?** `ProviderResolver.detect()`. If none → a screen
   explaining Floret needs a tasks provider, with one-tap links to install
   **OpenTasks** (FOSS) or **tasks.org**, plus "I use DAVx5 — set its Tasks app".
   (This is Floret's "needs DAVx5" moment. Disappears entirely under Posture B.)
2. **Tasks read/write permission** — request the active provider's runtime perms
   (`org.dmfs.permission.*` etc.). Declared in the manifest *and* requested at
   runtime; resolved dynamically from the detected provider.
3. **POST_NOTIFICATIONS + exact-alarm** — for due reminders (reuse Calendula's
   reminder onboarding).

`<queries>` in the manifest for package visibility (launch DAVx5 / OpenTasks),
exactly like Calendula's launcher query.

---

## 6. Reminders — the one pattern that does NOT carry over

Calendula relies on the **calendar provider broadcasting `EVENT_REMINDER`** and
just posts the notification (Etar model). **Tasks providers do not broadcast
reminders.** So Floret must schedule its own:

- `DueReminderScheduler` (AlarmManager, exact alarms via `USE_EXACT_ALARM` /
  `SCHEDULE_EXACT_ALARM`) sets an alarm per task at `DUE` minus the chosen
  offset (and optionally at `DTSTART`).
- `DueReminderReceiver` fires → posts via `TaskNotifier` (the copied
  `ReminderNotifier`), tapping opens the task.
- **Reschedule triggers:** boot (`RECEIVE_BOOT_COMPLETED`), and the data-source
  `ContentObserver` firing on any task change (our edits *and* external sync) →
  recompute alarms for the near-future window. Keep a small scheduled-alarm
  registry in DataStore so we can diff like Calendula diffs reminder rows.

This is the single largest *new* subsystem. Everything downstream of it (channel,
notification building, POST_NOTIFICATIONS gating, dedupe-by-tag) is copied.

---

## 7. Manifest (vs Calendula)

```xml
<!-- declared statically; the active set is also requested at runtime -->
<uses-permission android:name="org.dmfs.permission.READ_TASKS"/>
<uses-permission android:name="org.dmfs.permission.WRITE_TASKS"/>
<uses-permission android:name="org.tasks.permission.READ_TASKS"/>
<uses-permission android:name="org.tasks.permission.WRITE_TASKS"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.USE_EXACT_ALARM"/>
<queries> … LAUNCHER intent (package visibility) … </queries>
```
Receivers: `DueReminderReceiver`, `BootRescheduleReceiver`, Glance widget
receiver. **No `EVENT_REMINDER` receiver** (doesn't apply).

---

## 8. Milestones

- **M0 — Skeleton.** Copy Gradle/version-catalog/theme/DI/app+activity from
  Calendula, rename to Floret. Builds, shows themed empty scaffold. *(~½ day)*
- **M1 — Read path.** `TasksContract` + `ProviderResolver` + `OpenTasksDataSource`
  + repository. Lists overview + task list render real synced tasks (read-only),
  live-updating via ContentObserver. *(the meaty milestone)*
- **M2 — Complete & CRUD.** Toggle complete (swipe + checkbox), create via inline
  field + edit screen, delete. Local-list creation.
- **M3 — Detail/edit polish.** Due/start pickers, priority, percent, conflict-safe
  saves, smart lists (Today/Upcoming/Overdue).
- **M4 — Subtasks.** `RELATED-TO` read + indentation; create/reparent. *(trickiest)*
- **M5 — Reminders.** `DueReminderScheduler` + receivers + onboarding.
- **M6 — Settings, widget, i18n, F-Droid metadata, CI** (clone Calendula's
  `.gitea/workflows`, `fdroid-metadata/`, RELEASING docs).
- **B (separate, later):** add `:provider` module bundling Apache-2.0
  `opentasks-provider`; `ProviderResolver` default authority becomes our own
  `org.dmfs.tasks`; add sync-adapter permissions; ship self-contained. UI/repo
  untouched.

**Effort:** M0–M3 is a small, fast core (days, given the Calendula head start).
M4 (subtasks) and M5 (reminders) are the two spots needing real thought.

---

## 9. Open decisions / to verify

1. **Name** — `Floret` is the working title (florets compose a Calendula head).
   Confirm or replace before M0 (touches appId, namespace, package).
2. **tasks.org provider authority** — verify on a real device; OpenTasks
   (`org.dmfs.tasks`) is the certain target for v1.
3. **jtx Board** — support its richer contract later, or stay OpenTasks-only?
4. **B authority choice** — bundling `org.dmfs.tasks` makes Floret a *replacement*
   for OpenTasks (one authority owner per device). Intended (one app instead of
   two), but a conscious choice.
5. **Repo home** — sibling Gitea repo next to Calendula, MIT license, same CI.

---

## 10. Sync sources Floret inherits for free (README copy)

Because Floret builds on the provider, not on any one sync app, it works with
**anything that writes to the tasks provider**: DAVx5 (CalDAV), SmoothSync,
CalDAV-Sync, DecSync CC, and any Android sync adapter — no per-app integration.
Google Tasks / Microsoft To Do are out of scope by design (proprietary, would
mean owning a sync stack). Open standards — CalDAV / iCalendar / DecSync — are
the lane.

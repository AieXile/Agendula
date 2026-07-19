# Agendula — architecture

This document describes how Agendula is built **as it stands today**. For the
*why* behind the big decisions and the long-term plan, see
[`PLAN.md`](PLAN.md); for status and what's next, see [`ROADMAP.md`](ROADMAP.md).

---

## 1. The thesis in one sentence

Agendula is a Material 3 Expressive **front-end** over the OpenTasks
`TaskContract` provider — it reads, writes, and reminds on top of a tasks store
that some other app (DAVx5, SmoothSync, DecSync CC, tasks.org, …) syncs over
CalDAV. **Agendula owns no database and no sync stack.** It is the task-list
sibling to [Calendula](https://gitea.jeanlucmakiola.de/makiolaj/calendula),
which does the same thing for `CalendarContract`.

The whole design hangs off one rule:

> The entire app talks to a `TasksRepository`. Only the data layer knows there
> is a `ContentResolver`, a `TaskContract`, or an authority string behind it.
> **Provider column names and the authority string never leak above the data
> layer.**

This is what lets "Posture A" (front-end over an installed provider) become
"Posture B" (bundle the Apache-2.0 provider, be self-contained) without touching
the UI, the ViewModels, or the domain. See §7.

---

## 2. Layers

```
            ┌──────────────────────────────────────────────┐
  UI        │  Compose screens  +  ViewModels  (ui/*)       │
            │  RootScreen → permission gate → ListsScreen   │
            └───────────────┬──────────────────────────────┘
                            │  domain models + Flows only
            ┌───────────────▼──────────────────────────────┐
  Domain    │  Models, TaskForm, TaskFilter, TaskSorting,   │
            │  DayWindow  (pure Kotlin, no Android)         │
            └───────────────┬──────────────────────────────┘
                            │  TasksRepository (interface)
            ┌───────────────▼──────────────────────────────┐
  Data      │  TasksRepositoryImpl                          │
            │    └ TasksDataSource (interface)              │
            │        └ AndroidTasksDataSource               │
            │            └ ContentResolver / TaskContract / │
            │              ProviderResolver / ContentObserver│
            │  reminders/  prefs/  di/  demo/               │
            └───────────────┬──────────────────────────────┘
                            │  content:// + dangerous perms
            ┌───────────────▼──────────────────────────────┐
  External  │  OpenTasks provider  ←sync←  DAVx5 / DecSync… │
            └──────────────────────────────────────────────┘
```

The seam that matters is the pair of interfaces in the data layer:

- **`TasksRepository`** — the only type the UI sees. Flow-based reads, suspend
  writes. (`data/tasks/TasksRepository.kt`)
- **`TasksDataSource`** — the JVM-testable interface that does the actual
  provider work; `AndroidTasksDataSource` is the only Android-coupled
  implementation.

Both are bound in Hilt in `data/di/DataModule.kt`.

---

## 3. Module & package layout

Single `:app` module (Posture A). Package root `de.jeanlucmakiola.agendula`.

| Package | Contents |
|---|---|
| `domain/` | `Models` (TaskList, Task, TaskDetail, enums + pure iCal↔domain value mappers), `TaskForm` (validated create/edit), `TaskFilter` + `TaskFiltering` (smart lists), `TaskSorting`, `DayWindow` (local-midnight maths). No Android imports. |
| `data/tasks/` | `TasksContract` (vendored subset), `ProviderResolver` (the A/B seam), `TaskProjections`, `ColumnReader`, `TaskMapper` (cursor→domain), `TaskWriteMapper` (form→`ContentValues`), `TasksDataSource` + `AndroidTasksDataSource`, `TasksRepository` + `Impl`, `Failures`. |
| `data/reminders/` | `ReminderScheduler` (the self-scheduled engine), `DueReminderReceiver`, `BootReceiver`, `ProviderChangeReceiver`, `ScheduledReminderStore`, `TaskNotifier`. |
| `data/prefs/` | `SettingsPrefs` (DataStore). |
| `data/di/` | `DataModule` (binds + provides), `Qualifiers` (`@IoDispatcher`). |
| `data/demo/` | `DemoSeeder` (debug-only sample data). |
| `ui/` | `theme/`, `common/` (GroupedList, ListChip), `lists/`, `tasklist/`, `detail/`, `edit/`, `settings/`, `permission/` (each a ViewModel + UiState; `lists` also has its screen), `RootScreen`. |
| root | `AgendulaApp` (Hilt app), `MainActivity`. |

---

## 4. The data layer (the heart)

### 4.1 Provider targeting — `ProviderResolver`

`ProviderResolver.resolve()` walks a preference-ordered candidate list and
returns the first provider actually installed (via
`PackageManager.resolveContentProvider`), or `null` if none is. Each candidate
is a `TaskProvider(authority, readPermission, writePermission, packageName)`.

| Provider | Authority | Permissions |
|---|---|---|
| OpenTasks | `org.dmfs.tasks` | `org.dmfs.permission.READ_TASKS` / `WRITE_TASKS` |
| tasks.org | `org.tasks.opentasks` | `org.tasks.permission.READ_TASKS` / `WRITE_TASKS` |

Both are backed by the same dmfs `TaskProvider`, so the **same `TaskContract`
columns apply** regardless of which is present. `null` from `resolve()` drives
the "install a tasks provider" onboarding gate. `hasPermission()` checks both
runtime perms for the active provider.

### 4.2 `TasksContract`

A vendored subset of the Apache-2.0 OpenTasks `TaskContract` — column names,
table paths, status/priority constants, the local-account type. Agendula does
**not** take a runtime dependency on OpenTasks; the authority is injected from
`ProviderResolver`, never hardcoded in the contract.

### 4.3 Reads — Instances + `ContentObserver`

`AndroidTasksDataSource` queries the denormalized **instances** view (so each
occurrence is a row with the joined list colour, account, etc.), maps each
cursor row through `ColumnReader` → `TaskMapper` → domain `Task`, and exposes
the result as a `Flow`. A `ContentObserver` on the active authority's
Tasks/TaskLists URIs bridges into the Flow via `callbackFlow`, so **any change
re-emits** — Agendula's own writes *and* external sync (DAVx5 pulling new tasks)
update the UI live, and multiple sync sources coexist in one list.

### 4.4 Writes — repository API

```kotlin
interface TasksRepository {
    fun taskLists(): Flow<List<TaskList>>
    fun tasks(filter: TaskFilter): Flow<List<Task>>
    fun taskDetail(taskId: Long): Flow<TaskDetail?>

    suspend fun createTask(form: TaskForm): Long
    suspend fun updateTask(taskId: Long, form: TaskForm)
    suspend fun setCompleted(taskId: Long, completed: Boolean)   // the core gesture
    suspend fun deleteTask(taskId: Long)
    suspend fun createLocalList(name: String, color: Int): Long

    fun providerStatus(): ProviderStatus   // READY | NEEDS_PERMISSION | NO_PROVIDER
}
```

`TaskWriteMapper` turns a validated `TaskForm` into `ContentValues`. Completion
sets `STATUS = COMPLETED` (+ percent/completed timestamp); DAVx5 syncs that back
out as a normal VTODO status change. Writes to local/unsynced lists use the
sync-adapter URI form where the provider requires it.

### 4.5 Domain model notes

- `Task.id` is the **instance** row id; `Task.taskId` is the underlying
  `tasks._id` and the stable target for edits/completion.
- Subtasks are carried via `parentId` (`RELATED-TO` / `RELATION_TYPE_PARENT`);
  `TaskDetail` bundles a task with its direct children.
- `effectiveColor` = the task's own colour, else the list colour.
- iCal priority is bucketed to `NONE/LOW/MEDIUM/HIGH`; status maps to a
  4-value enum. Both mappings are pure functions in `Models.kt`, unit-tested.

---

## 5. Smart lists, filtering, sorting

`TaskFilter` is either `OfList(listId)` or `Smart(SmartList)`. The smart lists —
`ALL, TODAY, UPCOMING, OVERDUE, NO_DATE, COMPLETED` — are computed from due
dates, not membership. `TaskFiltering.matches()` is a **pure predicate** taking
`todayStart`/`todayEnd` (local-midnight bounds from `DayWindow`), so it
unit-tests with a fixed clock. `TaskSorting` orders within a list (due /
priority / etc.). None of this touches Android, which is why it's all in
`domain/`.

---

## 6. Reminders — the one subsystem that does NOT mirror Calendula

Calendula relies on the calendar provider broadcasting `EVENT_REMINDER`. **Tasks
providers broadcast nothing**, so Agendula schedules its own (`data/reminders/`):

- **`ReminderScheduler.sync()`** reads upcoming, non-closed, due-dated tasks
  within a rolling **30-day window**, computes each trigger as `due − lead`
  (lead from `SettingsPrefs`), and **diffs against `ScheduledReminderStore`** so
  only changed alarms move. It bails and clears everything if the provider is
  absent or unpermissioned.
- Alarms are exact where allowed (`setExactAndAllowWhileIdle`, falling back to
  `set` when `canScheduleExactAlarms()` is false), keyed by `taskId`.
- **`DueReminderReceiver`** fires → posts via `TaskNotifier` (channel,
  `POST_NOTIFICATIONS` gate, dedupe-by-tag).
- **Re-sync triggers:** app start, **`BootReceiver`** (re-arm after reboot), and
  **`ProviderChangeReceiver`** (`PROVIDER_CHANGED` on both authorities → external
  sync changed the data). The store lets each run diff like Calendula diffs
  reminder rows.

This is the single largest piece of genuinely-new code in Agendula.

---

## 7. The A / B seam (why the layering is shaped this way)

- **Posture A (today):** front-end over whatever provider is installed. Ships
  fast; requires a provider app present (the "needs DAVx5/OpenTasks" onboarding
  moment).
- **Posture B (later):** add a `:provider` module bundling the Apache-2.0
  `opentasks-provider`. `ProviderResolver` then finds **our own** `org.dmfs.tasks`
  first; external CalDAV engines sync directly into it. **The UI, ViewModels,
  domain, and `TasksRepository` do not change** — only the resolver's default and
  some manifest perms.

Bundling the provider bundles **storage, not sync** — Agendula stays a pure
front-end over open backends either way.

---

## 8. UI

Compose + Material 3 **Expressive** (`MaterialExpressiveTheme`,
`MotionScheme.standard()`, dynamic colour with a hand-tuned warm-mauve
fallback in `ui/theme/`). Each screen area (`lists`, `tasklist`, `detail`,
`edit`, `settings`, `permission`) has a ViewModel + immutable `UiState`;
`ListsScreen` is the first rendered surface.

`RootScreen` is the entry composable: it gates on `ProviderStatus`
(`NO_PROVIDER` / `NEEDS_PERMISSION` → onboarding `Gate`; `READY` →
`ListsScreen`). The remaining screens are being built one at a time — their
ViewModels exist and are tested against the real data layer; navigation
callbacks are currently stubs (see [`ROADMAP.md`](ROADMAP.md)). Follow the
`material-3` skill for component choices (M3 `ListItem` rows, expressive
checkbox/FAB/swipe motion).

---

## 9. Dependency injection

Hilt, `SingletonComponent`. `DataModule` has a `@Binds` module
(`TasksDataSource` → `AndroidTasksDataSource`, `TasksRepository` →
`TasksRepositoryImpl`) and a `@Provides` module (the `agendula_prefs` DataStore,
the `@IoDispatcher`). `AgendulaApp` is the `@HiltAndroidApp` entry point;
`MainActivity` is `@AndroidEntryPoint`. ViewModels get the repository injected.

---

## 10. Build & tooling

| | |
|---|---|
| Build | AGP 9.2.1, Kotlin 2.3.21, KSP, Hilt 2.59.2, Java 17 |
| SDK | compileSdk 37, minSdk 29 (Android 10), targetSdk 36 |
| UI | Compose BOM 2026.05.01, Material3 `1.5.0-alpha21` (Expressive APIs), Glance 1.1.1 (widget, later) |
| Other | DataStore, kotlinx-datetime, kotlinx-coroutines |
| Tests | JUnit5 (Jupiter) + Truth + Turbine + coroutines-test; the data source is the JVM-testable seam |
| Versioning | committed `versionName` is the source of truth; a bump reaching `main` triggers the release and the pipeline mints the `vX.Y.Z` tag. `versionCode = MAJOR*10000 + MINOR*100 + PATCH`. See [`RELEASING.md`](RELEASING.md). |
| CI | Gitea workflows (`.gitea/workflows/ci.yaml`, `release.yaml`) |
| Distribution | F-Droid (`fdroid-metadata/`) |

---

## 11. Manifest surface

- **Permissions:** both `org.dmfs.*` and `org.tasks.*` read/write tasks perms
  declared statically (the active set is requested at runtime);
  `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, exact-alarm
  (`USE_EXACT_ALARM` on 33+, `SCHEDULE_EXACT_ALARM` ≤32).
- **`<queries>`** for package visibility: both provider authorities + a LAUNCHER
  intent (so `resolveContentProvider` works and onboarding can open the
  provider / a store listing).
- **Receivers:** `DueReminderReceiver` (not exported), `BootReceiver`,
  `ProviderChangeReceiver` (both authorities). No `EVENT_REMINDER` receiver —
  that's a Calendula thing that doesn't apply here.

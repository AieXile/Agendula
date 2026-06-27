# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); the latest released git tag is
the source of truth for version codes (see Calendula's `docs/RELEASING.md`).

## [Unreleased]

### Added
- M5 reminders onboarding & polish: a one-time reminder onboarding step after the
  provider grant explains that Agendula delivers due reminders itself and requests
  `POST_NOTIFICATIONS` (API 33+). A new **Settings** screen (gear on the lists
  overview), structured after Calendula as a category hub with sliding sub-screens
  (`CollapsingScaffold` + grouped category rows + full-screen `OptionPicker`):
  an About card, **Appearance** (theme, dynamic colour), **Task form** (which
  optional edit-form fields show by default, default list, the add-a-subtask-row
  opt-out), and **Reminders** (a master enable switch that re-requests the
  notification permission, a "when to remind" default offset, and — on Android 12
  only — an exact-alarm status row that deep-links to system settings).
- The reminders master switch gates the whole engine: turning it off clears every
  scheduled alarm and suppresses any that fire.
- Expressive top-bar actions: a reusable `ShapedActionButton` wraps an action icon
  in a tonal M3 Expressive `MaterialShapes` container (the settings entry uses the
  faceted Gem shape, tertiary tones), with a small press scale/rotate flourish.
  Future top-bar actions pick their own shape from the `ActionShapes` registry.

## [0.2.0] - 2026-06-27

### Added
- M2 Material 3 Expressive UI — the app is fully navigable now: the
  provider/permission onboarding gate, the lists overview (smart lists + user
  lists grouped by account), and the task list (swipe-to-complete / -delete,
  inline add, smart-list section headers), detail, and create/edit screens, all
  wired to the M1 ViewModels.
- M3 detail/edit polish: a "Progress" slider (percent-complete, 5% detents,
  written to `Tasks.PERCENT_COMPLETE`) and conflict-safe saves — `updateTask`
  re-checks the provider's `last_modified` against the value captured when the
  form loaded and surfaces an overwrite-or-cancel prompt instead of clobbering an
  external change (e.g. a DAVx5 sync).
- M4 subtasks (UI): reparent — a full-width, searchable "Parent task" sheet on
  the edit form, with candidates grouped by due-date section, files a task under
  any active top-level task in its list (or "None" to promote it); switching list
  clears the now-invalid parent. Tapping a subtask in the detail screen opens its
  own detail, which shows a "Part of …" parent card. On the task list a parent has
  a dedicated expand button that reveals its children as a nested grouped run,
  ending with an inline "add a subtask" row (an opt-out toggle is planned for the
  M6 settings screen).
- Priority is coloured by level (green / amber / red pastels) on the list, detail,
  and edit screens.

### Fixed
- Overview open-counts now count top-level tasks only, so subtasks — and
  especially open subtasks under a completed parent — no longer inflate a list's
  "N open" or the smart-list counts.
- Swipe-to-delete now reveals its red background + icon as you drag (tracking the
  live direction, not just the settled target), and a floating "Deleted · Undo"
  chip defers the actual delete so it can be restored.

## [0.1.0] - 2026-06-18

### Added
- M1 data layer + logic ("backoffice"): the full non-visual stack over the
  OpenTasks `TaskContract` provider (authority resolved at runtime —
  `org.tasks.opentasks` for tasks.org, `org.dmfs.tasks` for OpenTasks).
  - Vendored `TaskContract` subset, `ProviderResolver`, `ColumnReader`, mappers,
    `AndroidTasksDataSource` (Instances query + ContentObserver), and
    `TasksRepository` exposing live Flows of lists/tasks/detail.
  - Domain models, smart-list filtering (Today/Upcoming/Overdue/No-date/All/
    Completed), sorting, form validation, sub-tasks via `parent_id`.
  - Self-scheduled due-reminder engine (AlarmManager + boot/provider-change
    re-sync), notifications, DataStore prefs.
  - Render-only ViewModels + UiState for every screen (lists, task list, detail,
    edit, settings, permission) so the UI is build-only.
  - 24 JVM unit tests (mappers, filtering, sorting, form, value mapping, day
    windows). `RootScreen` is a functional scaffold over the real data, to be
    replaced screen-by-screen with the Material 3 Expressive design.
- M0 skeleton: project scaffolding copied from Calendula (Gradle, version
  catalog, Hilt, Material 3 Expressive theme, Gitea CI/release workflows),
  reseeded to a warm-mauve palette. Builds and shows a themed placeholder.

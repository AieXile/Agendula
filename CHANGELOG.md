# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); the `versionName` committed in
`app/build.gradle.kts` is the source of truth for a release (see
`docs/RELEASING.md`), and the `vX.Y.Z` tag is minted by the pipeline.

## [Unreleased]

## [0.3.0] - 2026-07-19

### Added
- Reminders: Agendula now delivers your due reminders itself. A one-time setup
  step explains this and asks for notification access, and a master switch in
  Settings turns the whole thing off again.
- A Settings screen, from the gear on the overview: appearance and theme, which
  fields the task form shows, your default list, and reminder defaults.
- The overview leads with Today — a progress ring showing how much of today
  you've finished — followed by a live preview of what's coming up next.
- Search across every task, open or completed, from the top bar.
- A proper app icon.

### Changed
- A tidier top bar: no app title, with search and settings pinned to the right.

## [0.2.2] - 2026-07-19

### Fixed
- Release automation now reliably mirrors each release to the Codeberg mirror
  (signed APK + SHA-256 checksum). The 0.2.1 attempt failed when the release tag
  had already been synced to Codeberg.

## [0.2.1] - 2026-07-19

### Added
- Releases are now also published to the Codeberg mirror as a direct download:
  each release carries the signed APK plus a SHA-256 checksum, for users who
  don't use F-Droid.

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

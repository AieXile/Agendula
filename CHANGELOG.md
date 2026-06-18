# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); the latest released git tag is
the source of truth for version codes (see Calendula's `docs/RELEASING.md`).

## [Unreleased]

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

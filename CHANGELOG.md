# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); the `versionName` committed in
`app/build.gradle.kts` is the source of truth for a release (see
`docs/RELEASING.md`), and you tag the matching `vX.Y.Z` yourself when you
publish a build.

Entries below `1.2.0` describe upstream releases and are kept as history, so
they may still mention the upstream project's infrastructure (Codeberg,
Weblate, F-Droid). This fork's own changes are listed in [FORK.md](FORK.md).

## [Unreleased]

### Changed
- The home screen's search, settings and new-list actions are plain round tonal
  buttons again. They no longer use the scalloped Material Shapes, and pressing
  one only dips it — the shape no longer spins and the add action no longer
  morphs before it fires.
- The home screen's three tiles (today's progress, overdue and all) are replaced
  by one scrollable feed of every open task, with its header opening the full
  list. The "Upcoming" preview below is gone, as the feed already shows those
  tasks.
- Settings no longer shows the separate "License" link beside Source and
  Privacy; the open-source notices keep their own row.

### Fixed
- Going back from a second-level screen is no longer a blank cut: the page
  slides out to the right over the page it returns to, which grows back in
  behind it. Opening one now leaves the page underneath receding to 95% rather
  than sitting unchanged, and the settings hub's sub-screens move the same way.
  Both directions follow the M3 Expressive motion scheme and share one
  definition in `ui/navigation/NavTransitions.kt`, dropping to a plain fade
  when the system "remove animations" setting is on.
- The crash-report dialog and the static widget previews in the widget picker
  now follow the app's language instead of staying English under Chinese.
- The heading above the lists of the device-only store now reads "This device"
  instead of the data layer's `Local` placeholder.
- The undo / confirmation chip's drop shadow falls away from the pill like any
  other floating control, instead of being cut off at the chip's edges.

## [1.2.0] - 2026-10-26

### Added
- A reminder can count from the task's start as well as from its due date, and
  tasks with only a start date get reminders too. Default reminders can mix
  both, e.g. a day before the start and an hour before the due date; each
  applies to tasks that have its date (#72).
- An all-day task's reminders can each have their own time, so one task can
  remind you at 09:00 and again at 17:00 (#78).
- Other apps can read the tasks kept in Agendula's own database through an
  OpenTasks-compatible provider, once you grant them permission. Apps that
  already support OpenTasks or tasks.org need only a small change to support
  Agendula too (#43).
- With a second permission, other apps can also add, edit, complete and delete
  those tasks, including their reminders and tags. They can tick off a single
  occurrence of a recurring task. Task lists stay read-only for them (#63).

### Changed
- A new task no longer takes a default reminder whose time has already
  passed, so a start of "today" does not ring the moment the task is saved.
- All-day default reminders are whole days; one set in hours rounds to the
  nearest day.
- Switching a task between timed and all-day converts its reminders right
  away in the editor, so what you see is what gets saved.
- The editor shows the default reminders a new task, or a newly added date,
  will get, before you save. Removing them leaves the task without a reminder
  instead of falling back to the default.
- Reminders now sync. A reminder set on a task is uploaded as a standard alarm
  (`VALARM`), so it also notifies in macOS and iOS Reminders, Thunderbird,
  tasks.org and jtx Board, and alarms set in those apps now fire in Agendula
  instead of the default reminder. Removing a task's alarm in another app
  turns it off here too, rather than falling back to the default (#52).
- A new task with a date, or one that gets a new date, takes the default
  reminder for that date as its own, so it travels with the task to the server.
  Changing the default later no longer changes those tasks; tasks that rely on
  the default today are left as they are.
- On upgrade, reminders on open, synced tasks that so far stayed on this
  device are uploaded once.
- Alarms imported from an `.ics` file become reminders on the task.
- Screens now open and close the way they do in Calendula: they slide in, and
  a back swipe previews the screen behind before closing (#66).
- Settings now use Calendula's names and layout. Reminders is now
  Notifications, Task form is now New task form, and export and import moved
  out of Storage into a new Backup & restore section (#66).

### Fixed
- Fixed a crash when a task's date was shown in Russian and other languages
  whose dates put a word after the year (#70).
- Widgets no longer turn into the Today widget or show "Can't load content"
  after a task is ticked off (#92).
- Ticking off a task in the Tasks and Up next widgets now works in release
  builds; until now the tap did nothing (#75).
- The Tasks widget leaves the same gap under its header as its preview (#86).
- The Starts and Due labels in the task form no longer get cut off at large
  font sizes (#76).
- Adding an account by email address finds the server again for providers
  that publish it in DNS, such as mailbox.org and Posteo. The DNS lookup never
  reached the network, so they ended in "isn't a CalDAV server". It now goes
  through Android's resolver and respects Private DNS (#97).
- When the server can't be found from your email address, account setup asks
  for the server address straight away and keeps your email as the username,
  instead of stopping at an error (#97).

## [1.1.0] - 2026-10-10

### Added
- Four new home-screen widgets: Today, Up next, Lists and Week.
- A widget can show tasks from several lists at once, optionally grouped by
  list.
- A setting under Settings → Task form pre-fills a new task's start with today.
- An offline version of Agendula with no sync and no network access at all,
  published as a separate APK on Codeberg (#39).
- Tags. Add them while editing a task, see them on the task and in lists,
  browse them from the home screen and filter any list by them. They are saved
  as `CATEGORIES`, so they sync with Nextcloud Tasks, tasks.org and DAVx⁵ (#35).
- Filters. Save your own view of the open tasks by due date (overdue, today,
  upcoming, no date, in any combination), lists, tags and minimum priority.
  They live on the home screen and can be shown in the Tasks widget, so today
  and overdue tasks can now share one widget (#47).

### Changed
- The Tasks widget has agenda-style rows, an optional row colour and an "All
  caught up" state. Ticking a task in any widget shows it as done for a moment
  before it disappears.
- The "At a glance" widget is gone; the Tasks and Today widgets cover what it
  showed.
- Synced lists can be renamed and deleted, not only device-only ones. A
  read-only share shows the edit button greyed out and explains why on tap.
- The sort button in task lists now opens "Sort and filter", which can also
  filter by tag.
- While editing a task, its title moves into the top bar once you scroll past
  it.

### Fixed
- The list editor's Where and smart-lists rows no longer have double padding.
- A field you tap now scrolls fully above the keyboard, and the "add subtask"
  row in task lists no longer ends up hidden behind it.
- All-day tasks west of UTC no longer show up a day early in Overdue, Today
  and Upcoming.

## [1.0.0] - 2026-09-21

### Added
- CalDAV sync built in: Nextcloud, Radicale, Baïkal and more.
- Agendula keeps your tasks itself, no other app needed. Copy them over from
  OpenTasks or tasks.org in Settings → Storage.
- Repeating tasks, several reminders per task, lists managed in the app,
  iCalendar import and export, a home-screen widget and a Quick Settings tile.

### Changed
- New settings for all-day reminders, snooze length, sync interval, time format
  and week start.

## [0.4.0] - 2026-08-31

### Added
- Agendula now speaks **German** and **Brazilian Portuguese**, the first
  community translations. Pick one under **Settings → App language**, or in
  Android's own per-app language settings; leave it on *System default* and
  Agendula follows your phone. Anything not yet translated falls back to
  English.
- Agendula can now be translated. Pick or request a language on Weblate and
  translate in the browser — the link sits at the top of the language picker in
  **Settings → App language**. Partial translations are fine; anything
  untranslated falls back to English.

### Changed
- Agendula's home is now **Codeberg** (`jlmakiola/agendula`) — that's where the
  source, issues, pull requests and releases live. The Source and License links
  in Settings, the issue-reporting link and the F-Droid metadata all point there
  now. The self-hosted Gitea instance stays as build infrastructure.

## [0.3.2] - 2026-07-20

### Fixed
- Releases reach the Codeberg download channel again. 0.3.1 published to
  F-Droid but never appeared on Codeberg, so if you install from there — or
  through Obtainium — this is the release that finally carries 0.3.0's
  launch-crash fix. The app itself is unchanged from 0.3.1.

## [0.3.1] - 2026-07-20

### Fixed
- Agendula no longer crashes on launch. Every 0.3.0 install was affected: the
  release build stripped a constructor that the background-work scheduler needs
  to open its database, and that happens before the app draws anything.

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

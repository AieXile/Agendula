# Using Agendula's tasks from another app

When Agendula keeps tasks in its own database (the default storage mode), it
publishes them through a `ContentProvider` that follows the
[OpenTasks contract](https://github.com/dmfs/opentasks/blob/master/opentasks-contract/src/main/java/org/dmfs/tasks/contract/TaskContract.java).
An app that already works with OpenTasks or tasks.org can work with Agendula by
using a different authority and permissions.

| | |
|---|---|
| Authority | `de.jeanlucmakiola.agendula.tasks` (offline build: `de.jeanlucmakiola.agendula.offline.tasks`) |
| Read permission | `de.jeanlucmakiola.agendula.permission.READ_TASKS` (offline build: `de.jeanlucmakiola.agendula.offline.permission.READ_TASKS`) |
| Write permission | `de.jeanlucmakiola.agendula.permission.WRITE_TASKS` (offline build: `de.jeanlucmakiola.agendula.offline.permission.WRITE_TASKS`) |
| Protection level | `dangerous`: request them at runtime |
| Package visibility | add `<provider android:authorities="de.jeanlucmakiola.agendula.tasks" />` to your `<queries>` |

Both names are derived from the installed package name, so every build has its
own and they can be installed side by side: the authority is
`<package>.tasks` and the permissions are `<package>.permission.READ_TASKS` and
`<package>.permission.WRITE_TASKS`.

| Build | Package |
|---|---|
| Release | `de.jeanlucmakiola.agendula` |
| Release, offline | `de.jeanlucmakiola.agendula.offline` |
| Debug | `de.jeanlucmakiola.agendula.debug` |
| Debug, offline | `de.jeanlucmakiola.agendula.offline.debug` |
| Release test | `de.jeanlucmakiola.agendula.releasetest` |
| Release test, offline | `de.jeanlucmakiola.agendula.offline.releasetest` |

## Tables

| URI | Rows |
|---|---|
| `content://<authority>/tasklists` | one per list |
| `content://<authority>/tasks` | one per task; a modified occurrence of a recurring task is its own row with `original_instance_id` / `original_instance_time` set |
| `content://<authority>/instances` | one per occurrence: recurring tasks are expanded up to one year back and two years ahead, and modified occurrences replace the ones they override |
| `content://<authority>/properties` | reminders (`vnd.android.cursor.item/alarm`) and tags (`vnd.android.cursor.item/category`) |

Each table also accepts `/<id>`. Column names, value encodings (epoch millis,
all-day dates as UTC midnight, `status`, `priority`, `class`) and MIME types
are the OpenTasks ones. `selection`, `selectionArgs` and `sortOrder` work as
they do against OpenTasks.

## Writing

Writes need the write permission and go through the same code as edits made in
Agendula itself: a changed task is marked for the next CalDAV sync, reminders
are re-armed, and widgets refresh.

| Table | insert | update | delete |
|---|---|---|---|
| `tasklists` | no | no | no |
| `tasks` | yes | yes | yes |
| `instances` | no | see below | one occurrence |
| `properties` | alarms and tags | alarms and tags | alarms and tags |

`update` and `delete` take a `selection` like a query does, and it matches the
rows you can read. `insert` returns the new row's URI. `applyBatch` runs in one
transaction: if any operation fails, none of them is applied. A write that
changes nothing does not mark the task for sync.

**Columns.** A task write may set `list_id`, `_uid` (on insert only), `title`,
`description`, `location`, `url`, `priority`, `class`, `status`,
`percent_complete`, `completed`, `task_color`, `dtstart`, `due`, `duration`,
`is_allday`, `tz`, `rrule`, `rdate`, `exdate`, `parent_id` and `sorting`. Other
columns the table publishes are ignored, so you can write back a row you read.
A column the table does not have is rejected with `IllegalArgumentException`.
Agendula's sync state (the server's URL and ETag, the iCalendar `SEQUENCE`, and
properties it does not understand) is not published and cannot be written. A
timed task without a `tz` is floating and stays floating. A task inserted
without a `tz` is floating, as in OpenTasks; an existing task that first gets a
date takes the device's zone. `rdate` and `exdate` take comma-separated
iCalendar values of one kind: all dates, all UTC date-times or all local
date-times. A value written back exactly as read is kept, whatever its shape.

**Status.** `status`, `percent_complete` and `completed` stay consistent the way
OpenTasks keeps them. Completing a task sets 100 % and a completion time,
progress between 1 and 99 sets "in process", and a completion time alone
completes the task. When you write back a row you read, the column you changed
decides; the others it carries unchanged do not.

**Recurring tasks.** Writing `status`, `percent_complete` or `completed` to an
`instances` row completes, cancels or reopens that one occurrence; partial
progress is rejected. Other content columns written to an instance change the
whole series, and that occurrence too if it had been modified on its own.
Changing a time or recurrence column (`dtstart`, `due`, `duration`,
`is_allday`, `tz`, `rrule`, `rdate`, `exdate`) is rejected on an instance of a
recurring task, so move a series through `tasks`; writing those columns back
unchanged, as in a row you read, is fine. Moving a series' start or changing its
rule drops the occurrences that had been modified on their own, as it does in
the app. Deleting an `instances` row deletes that one occurrence.

**Subtasks.** A subtask has to be in its parent's list. Moving a task to another
list moves its subtasks with it, and deleting a task deletes them. A task in a
synced list stays as a hidden tombstone until the next sync removes it from the
server.

**Rejected writes** throw `IllegalArgumentException` for a bad value, a missing
list or parent, or a parent in another list. They throw
`UnsupportedOperationException` for lists and for tasks in a list the server
has made read-only, and `IllegalStateException` while Agendula works on top of
an external provider or is still starting. `list_access_level` does not tell
you which lists are read-only: OpenTasks reserves it and asks providers to
publish `0`.

## Behaviour

- **Change notifications.** Register a `ContentObserver` on
  `content://<authority>` with `notifyForDescendants = true`. It fires when
  tasks change or when the user switches storage mode.
- **External storage mode.** When the user has Agendula work on top of
  OpenTasks or tasks.org, every table is empty and writes are refused. Use that
  provider directly.
- **Ids are stable.** A task keeps its `_id`. An instance's `_id` is built
  from its series and its original time, and a property's from its alarm or
  its task and tag, so they stay the same across reads and within a batch for
  as long as that occurrence, alarm or tag exists. `distance_from_current`
  still moves with the clock, and observers are notified hourly when it does.

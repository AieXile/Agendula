#!/usr/bin/env python3
"""Build the dmfs `tasks.db` fixture the one-shot import is tested against.

The provider is deleted, so the import cannot
keep a real v0.3.x database around by asking the provider to create one. This
writes the schema the provider's TaskDatabaseHelper produces at DATABASE_VERSION
23 — tables `Lists`, `Tasks`, `Properties` only, which are the three the import
reads — and seeds a spread that covers what the import has to get right.

Regenerate with:  python3 scripts/make_import_fixture.py
"""

from __future__ import annotations

import os
import sqlite3
import sys

OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app/src/androidTest/assets/tasks-v23.db",
)

DDL = [
    """CREATE TABLE Lists (
        _id INTEGER PRIMARY KEY AUTOINCREMENT,
        account_name TEXT, account_type TEXT, list_name TEXT, list_color INTEGER,
        list_access_level INTEGER, visible INTEGER, sync_enabled INTEGER,
        list_owner TEXT, _dirty INTEGER DEFAULT 0, _sync_id TEXT,
        sync_version TEXT, sync1 TEXT, sync2 TEXT, sync3 TEXT, sync4 TEXT,
        sync5 TEXT, sync6 TEXT, sync7 TEXT, sync8 TEXT)""",
    """CREATE TABLE Tasks (
        _id INTEGER PRIMARY KEY AUTOINCREMENT, version INTEGER DEFAULT 0,
        list_id INTEGER NOT NULL, title TEXT, location TEXT, geo TEXT,
        description TEXT, url TEXT, organizer TEXT, priority INTEGER,
        task_color INTEGER, class INTEGER, completed INTEGER,
        completed_is_allday INTEGER, percent_complete INTEGER,
        status INTEGER DEFAULT 0, is_new INTEGER, is_closed INTEGER,
        dtstart INTEGER, created INTEGER, last_modified INTEGER,
        is_allday INTEGER, tz TEXT, due INTEGER, duration TEXT, rdate TEXT,
        exdate TEXT, rrule TEXT, parent_id INTEGER, sorting TEXT,
        has_alarms INTEGER, has_properties INTEGER, pinned INTEGER,
        original_instance_sync_id TEXT, original_instance_id INTEGER,
        original_instance_time INTEGER, original_instance_allday INTEGER,
        _dirty INTEGER DEFAULT 1, _deleted INTEGER DEFAULT 0, _sync_id TEXT,
        _uid TEXT, sync_version TEXT, sync1 TEXT, sync2 TEXT, sync3 TEXT,
        sync4 TEXT, sync5 TEXT, sync6 TEXT, sync7 TEXT, sync8 TEXT)""",
    """CREATE TABLE Properties (
        property_id INTEGER PRIMARY KEY AUTOINCREMENT, task_id INTEGER,
        mimetype INTEGER, prop_version INTEGER,
        data0 TEXT, data1 TEXT, data2 TEXT, data3 TEXT, data4 TEXT, data5 TEXT,
        data6 TEXT, data7 TEXT, data8 TEXT, data9 TEXT, data10 TEXT,
        data11 TEXT, data12 TEXT, data13 TEXT, data14 TEXT, data15 TEXT,
        prop_sync1 TEXT, prop_sync2 TEXT, prop_sync3 TEXT, prop_sync4 TEXT,
        prop_sync5 TEXT, prop_sync6 TEXT, prop_sync7 TEXT, prop_sync8 TEXT)""",
]

LOCAL = ("Local", "org.dmfs.account.LOCAL")
CALDAV = ("me@example.org", "bitfire.at.davdroid")
ALARM_MIMETYPE = "vnd.android.cursor.item/alarm"

# 2026-01-15T09:00:00Z, and a day in millis.
T0 = 1_768_467_600_000
DAY = 86_400_000


def seed(db: sqlite3.Connection) -> None:
    lists = db.executemany(
        "INSERT INTO Lists (_id, account_name, account_type, list_name, list_color,"
        " visible, sync_enabled, list_owner) VALUES (?,?,?,?,?,?,?,?)",
        [
            (1, *LOCAL, "Personal", 0xFF7A5C6B, 1, 1, None),
            (2, *LOCAL, "Hidden list", 0xFF445566, 0, 1, None),
            # An external account inside our own authority: only reachable if the
            # user pointed DAVx5 at us. Imported as a local list, UID preserved.
            (3, *CALDAV, "Work", 0xFF2244AA, 1, 1, "Me"),
        ],
    )
    del lists

    def task(**kw):
        cols = ", ".join(kw)
        marks = ", ".join("?" * len(kw))
        db.execute(f"INSERT INTO Tasks ({cols}) VALUES ({marks})", tuple(kw.values()))

    task(_id=1, list_id=1, title="Buy milk", due=T0 + DAY, status=0,
         _uid="a1b2c3d4-0000-4000-8000-000000000001", created=T0, last_modified=T0)
    # No UID: the import mints one.
    task(_id=2, list_id=1, title="Call the dentist", due=T0 + 2 * DAY, status=1,
         percent_complete=40, created=T0, last_modified=T0)
    task(_id=3, list_id=1, title="Gather receipts", parent_id=1, status=0,
         _uid="a1b2c3d4-0000-4000-8000-000000000003", created=T0, last_modified=T0)
    task(_id=4, list_id=1, title="Renew domain", status=2, percent_complete=100,
         completed=T0 - DAY, is_closed=1,
         _uid="a1b2c3d4-0000-4000-8000-000000000004", created=T0, last_modified=T0)
    task(_id=5, list_id=1, title="Water the plants", dtstart=T0, due=T0 + 3600_000,
         rrule="FREQ=WEEKLY;BYDAY=MO,TH", tz="Europe/Berlin",
         _uid="a1b2c3d4-0000-4000-8000-000000000005", created=T0, last_modified=T0)
    task(_id=6, list_id=1, title="Team offsite", dtstart=T0 - T0 % DAY,
         due=T0 - T0 % DAY + DAY, is_allday=1,
         _uid="a1b2c3d4-0000-4000-8000-000000000006", created=T0, last_modified=T0)
    # Deleted-but-unsynced: gone as far as the user is concerned, so not imported.
    task(_id=7, list_id=1, title="Cancelled thing", _deleted=1,
         _uid="a1b2c3d4-0000-4000-8000-000000000007", created=T0, last_modified=T0)
    task(_id=8, list_id=2, title="Task in a hidden list", status=0,
         _uid="a1b2c3d4-0000-4000-8000-000000000008", created=T0, last_modified=T0)
    task(_id=9, list_id=3, title="Ship the release", due=T0 + 5 * DAY, status=0,
         _uid="a1b2c3d4-0000-4000-8000-000000000009", created=T0, last_modified=T0,
         _sync_id="https://dav.example.org/tasks/9.ics")

    db.executemany(
        "INSERT INTO Properties (property_id, task_id, mimetype, data0, data1, data2, data3)"
        " VALUES (?,?,?,?,?,?,?)",
        [
            # data0 minutes before, data1 reference (1 = DUE), data3 alarm type.
            (1, 1, ALARM_MIMETYPE, "30", "1", None, "1"),
            (2, 9, ALARM_MIMETYPE, "1440", "1", "Ship it", "1"),
            # A non-alarm property the import must skip.
            (3, 1, "vnd.android.cursor.item/category", "Errands", None, None, None),
        ],
    )


def main() -> int:
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)
    db = sqlite3.connect(OUT)
    try:
        for statement in DDL:
            db.execute(statement)
        db.execute("PRAGMA user_version = 23")
        seed(db)
        db.commit()
    finally:
        db.close()
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

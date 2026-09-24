#!/usr/bin/env python3
"""Write sample task lists for store screenshots, dated relative to a given day.

    scripts/make_store_sample_tasks.py [YYYY-MM-DD]

Writes design/store/sample/<list>.ics, one list per file, for the in-app
iCalendar import. Defaults to today.
"""
import sys
from datetime import date, timedelta
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "design/store/sample"

# (summary, due offset in days or None, extras)
LISTS = {
    "Personal": [
        ("Renew passport", 0, {"PRIORITY": "1", "DESCRIPTION": "Photos are in the desk drawer. Book a slot at the town hall first."}),
        ("Call the dentist", 0, {}),
        ("Water the plants", 0, {"RRULE": "FREQ=DAILY;INTERVAL=3"}),
        ("Go for a run", 0, {"RRULE": "FREQ=WEEKLY;BYDAY=MO,WE,FR"}),
        ("Cancel the old gym membership", -2, {"PRIORITY": "1"}),
        ("Birthday gift for Sam", 3, {"PERCENT-COMPLETE": "33", "children": [
            ("Order the book", True), ("Wrap it", False), ("Write a card", False)]}),
        ("Plan the weekend hike", 4, {"LOCATION": "Saxon Switzerland"}),
        ("Read \"The Midnight Library\"", None, {"PERCENT-COMPLETE": "40"}),
        ("Back up the photos", None, {}),
        ("Pick up dry cleaning", -1, {"done": True}),
    ],
    "Work": [
        ("Send the Q3 report to Lena", -1, {"PRIORITY": "1"}),
        ("Review Alex's pull request", 0, {"PRIORITY": "5"}),
        ("Weekly team sync notes", 1, {"RRULE": "FREQ=WEEKLY;BYDAY=TH"}),
        ("Prepare slides for the quarterly review", 2, {"PRIORITY": "5", "PERCENT-COMPLETE": "50", "children": [
            ("Collect the numbers", True), ("Draft the outline", True),
            ("Design the charts", False), ("Rehearse once", False)]}),
        ("Book flights to the Lisbon conference", 6, {"LOCATION": "Lisbon", "URL": "https://example.org/conference"}),
        ("Update the onboarding docs", None, {"PRIORITY": "9"}),
        ("Plan the team offsite", -1, {"done": True}),
    ],
    "Home": [
        ("Take out the recycling", 1, {"RRULE": "FREQ=WEEKLY;BYDAY=TH"}),
        ("Fix the leaky tap", 4, {"DESCRIPTION": "Washer size is 1/2\". Turn off the water under the sink first."}),
        ("Pay the rent", "month", {"RRULE": "FREQ=MONTHLY;BYMONTHDAY=1", "PRIORITY": "1"}),
        ("Book the car service", 8, {}),
        ("Clean the gutters", None, {}),
        ("Change the bed sheets", 0, {"done": True}),
    ],
    "Groceries": [
        ("Oat milk", None, {}),
        ("Eggs", None, {}),
        ("Cherry tomatoes", None, {}),
        ("Fresh basil", None, {}),
        ("Coffee beans", None, {"PRIORITY": "1"}),
        ("Sourdough bread", None, {"done": True}),
        ("Parmesan", None, {"done": True}),
    ],
}


def ics_date(d):
    return d.strftime("%Y%m%d")


def todo(uid, summary, due, extras, stamp, parent=None, done=False):
    lines = ["BEGIN:VTODO", f"UID:{uid}", f"DTSTAMP:{stamp}", f"SUMMARY:{summary}"]
    if due is not None:
        lines.append(f"DUE;VALUE=DATE:{ics_date(due)}")
        if "RRULE" in extras:
            lines.append(f"DTSTART;VALUE=DATE:{ics_date(due)}")
    for key in ("PRIORITY", "PERCENT-COMPLETE", "LOCATION", "URL", "RRULE"):
        if key in extras:
            lines.append(f"{key}:{extras[key]}")
    if "DESCRIPTION" in extras:
        lines.append("DESCRIPTION:" + extras["DESCRIPTION"].replace(",", "\\,"))
    if parent:
        lines.append(f"RELATED-TO:{parent}")
    if done:
        lines += ["STATUS:COMPLETED", "PERCENT-COMPLETE:100", f"COMPLETED:{stamp}"]
    else:
        lines.append("STATUS:NEEDS-ACTION")
    lines.append("END:VTODO")
    return lines


def main():
    today = date.fromisoformat(sys.argv[1]) if len(sys.argv) > 1 else date.today()
    stamp = today.strftime("%Y%m%dT080000Z")
    next_first = (today.replace(day=1) + timedelta(days=32)).replace(day=1)
    OUT.mkdir(parents=True, exist_ok=True)
    for name, tasks in LISTS.items():
        lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Agendula//store sample//EN",
                 f"X-WR-CALNAME:{name}"]
        for i, (summary, offset, extras) in enumerate(tasks):
            due = next_first if offset == "month" else (
                None if offset is None else today + timedelta(days=offset))
            uid = f"sample-{name.lower()}-{i}"
            lines += todo(uid, summary, due, extras, stamp, done=extras.get("done", False))
            for j, (child, child_done) in enumerate(extras.get("children", [])):
                lines += todo(f"{uid}-{j}", child, None, {}, stamp, parent=uid, done=child_done)
        lines.append("END:VCALENDAR")
        (OUT / f"{name}.ics").write_text("\r\n".join(lines) + "\r\n", encoding="utf-8")
        print(OUT / f"{name}.ics")


if __name__ == "__main__":
    main()

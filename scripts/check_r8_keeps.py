#!/usr/bin/env python3
"""Fail when R8 keeps one of our classes by name but strips its no-arg constructor.

A keep rule that names a class but no members keeps the class and lets R8 drop
the constructor nothing calls directly. Anything that then instantiates it by
reflection (Room, Glance's ActionCallback, WorkManager) fails at runtime only,
in release builds only (#1, #75). R8 reports both halves: seeds.txt lists what
keep rules pinned, usage.txt what it removed.

    scripts/check_r8_keeps.py [mapping dir]   default: fullReleaseTest's
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / "app/build/outputs/mapping/fullReleaseTest"
PACKAGES = ("de.jeanlucmakiola.",)


def kept_classes(seeds: Path) -> set[str]:
    return {
        line.strip()
        for line in seeds.read_text().splitlines()
        if line.startswith(PACKAGES) and ":" not in line
    }


def classes_without_default_ctor(usage: Path) -> set[str]:
    stripped, current = set(), None
    for line in usage.read_text().splitlines():
        if not line.startswith(" "):
            current = line.rstrip(":") if line.endswith(":") else None
        elif current and line.strip().endswith(" <init>()"):
            stripped.add(current)
    return stripped


def main() -> int:
    mapping = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_DIR
    seeds, usage = mapping / "seeds.txt", mapping / "usage.txt"
    if not seeds.exists() or not usage.exists():
        print(f"ERROR: no seeds.txt/usage.txt in {mapping}; build a minified variant first.", file=sys.stderr)
        return 1
    broken = sorted(kept_classes(seeds) & classes_without_default_ctor(usage))
    for name in broken:
        print(f"ERROR: {name} is kept by a rule but R8 removed its no-arg constructor.", file=sys.stderr)
    if broken:
        print("Keep the constructor too: -keep class ... { <init>(); }", file=sys.stderr)
        return 1
    print(f"OK: every kept class under {', '.join(PACKAGES)} still has its no-arg constructor.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Validate Android translation resources against the base strings.xml.

Community translations live in ``app/src/main/res/values-<locale>/strings.xml``
and are produced via Weblate. This guard keeps incoming translation PRs honest:

  * every translation file must be well-formed XML;
  * a translation must not define keys absent from the base — those are stale
    keys left behind after a rename/removal upstream;
  * a translation must not translate strings marked ``translatable="false"`` in
    the base (URLs, IDs and the like).

Missing keys are *allowed* and only reported as coverage: a missing string
falls back to the English base at runtime, so partial translations are fine
(this mirrors the lint config, which downgrades ``MissingTranslation``).

Exits non-zero if any error is found. Errors are emitted as Gitea/GitHub
Actions ``::error`` annotations so they surface inline on the PR.
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES_DIR = Path("app/src/main/res")
BASE = RES_DIR / "values" / "strings.xml"
RESOURCE_TAGS = ("string", "plurals", "string-array")


def entries(path: Path) -> dict[str, bool]:
    """Map resource name -> is-translatable for every entry in ``path``."""
    root = ET.parse(path).getroot()
    return {
        el.attrib["name"]: el.attrib.get("translatable", "true") != "false"
        for el in root
        if el.tag in RESOURCE_TAGS and "name" in el.attrib
    }


def main() -> int:
    if not BASE.exists():
        print(f"::error::base resource file {BASE} not found", file=sys.stderr)
        return 1

    base = entries(BASE)
    base_keys = set(base)
    nontranslatable = {name for name, ok in base.items() if not ok}
    translatable_total = len(base_keys - nontranslatable)

    files = sorted(RES_DIR.glob("values-*/strings.xml"))
    if not files:
        print("No translation files found (values-*/strings.xml).")
        return 0

    errors = 0
    for path in files:
        locale = path.parent.name[len("values-"):]
        try:
            translated = entries(path)
        except ET.ParseError as exc:
            print(f"::error file={path}::{locale}: malformed XML: {exc}")
            errors += 1
            continue

        keys = set(translated)
        stale = sorted(keys - base_keys)
        translated_fixed = sorted(keys & nontranslatable)
        missing = base_keys - nontranslatable - keys

        for name in stale:
            print(f"::error file={path}::{locale}: stale key '{name}' is not in the base strings.xml")
            errors += 1
        for name in translated_fixed:
            print(
                f"::error file={path}::{locale}: key '{name}' is translatable=\"false\" "
                "in the base and must not be translated"
            )
            errors += 1

        covered = translatable_total - len(missing)
        pct = covered * 100 // translatable_total if translatable_total else 100
        verdict = "OK" if not (stale or translated_fixed) else "FAIL"
        print(f"{locale:<10} {covered}/{translatable_total} keys ({pct}%) — {verdict}")

    if errors:
        print(f"\n{errors} translation error(s) found.", file=sys.stderr)
        return 1
    print("\nAll translation files are consistent with the base.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

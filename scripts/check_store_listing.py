#!/usr/bin/env python3
"""Validate fastlane/metadata/android against what BOTH stores accept.

The fastlane tree is the single source for the F-Droid listing (official repo
harvests it, the self-hosted repo gets it via fastlane_to_fdroid_localized.sh)
and for the Play listing (`bundle exec fastlane listing`). F-Droid accepts
nearly anything; Play rejects a lot at upload time. Checking against Play's
rules here is what keeps the graphics shippable to both from the same files.

    scripts/check_store_listing.py             validate what exists
    scripts/check_store_listing.py --complete  also require everything a Play
                                               listing needs to go live

SDK- and dependency-free (PNG/JPEG headers are parsed by hand) so CI can run it
without setup.
"""
import re
import struct
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
META = ROOT / "fastlane/metadata/android"
RES = ROOT / "app/src/main/res"

# Play's limits; F-Droid truncates or recommends the same.
TEXT_LIMITS = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}
CHANGELOG_LIMIT = 500

# The fallback locale of both stores: F-Droid always, Play as the app's default
# language. It carries the full text and every graphic.
REQUIRED_LOCALES = ("en-US",)
IMAGE_LOCALE = "en-US"

SCREENSHOT_DIRS = ("phoneScreenshots", "sevenInchScreenshots", "tenInchScreenshots",
                   "tvScreenshots", "wearScreenshots")
# Play policy on the title: no ranking, price or promotional terms.
TITLE_BANNED = re.compile(r"\b(best|top|#1|no\.? ?1|free|sale|discount|new|hot|download now)\b", re.I)

errors, warnings = [], []


def err(path, msg):
    errors.append(f"{path.relative_to(ROOT)}: {msg}")


def warn(path, msg):
    warnings.append(f"{path.relative_to(ROOT)}: {msg}")


def image_info(path):
    """(format, width, height, has_alpha) or None if unreadable."""
    data = path.read_bytes()
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        w, h, _depth, color = struct.unpack(">IIBB", data[16:26])
        has_alpha = color in (4, 6) or b"tRNS" in data[:data.find(b"IDAT")]
        return "png", w, h, has_alpha
    if data[:2] == b"\xff\xd8":
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            seg_len = struct.unpack(">H", data[i + 2:i + 4])[0]
            if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return "jpeg", w, h, False
            i += 2 + seg_len
    return None


def check_image(path, *, size=None, alpha=None, max_mb, screenshot=False):
    info = image_info(path)
    if info is None:
        err(path, "not a PNG or JPEG")
        return
    fmt, w, h, has_alpha = info
    if size and (w, h) != size:
        err(path, f"is {w}x{h}, Play requires exactly {size[0]}x{size[1]}")
    if alpha is True and (fmt != "png" or not has_alpha):
        err(path, "must be a 32-bit PNG (with alpha channel)")
    if alpha is False and has_alpha:
        err(path, "has an alpha channel; Play wants 24-bit PNG or JPEG")
    if screenshot:
        short, long_ = sorted((w, h))
        if short < 320 or long_ > 3840:
            err(path, f"is {w}x{h}; each side must be 320–3840 px")
        if long_ > 2 * short:
            err(path, f"is {w}x{h}; the long side may be at most twice the short side")
        if short < 1080:
            warn(path, f"is {w}x{h}; Play only features screenshots at 1080 px or more")
    mb = path.stat().st_size / 1_000_000
    if mb > max_mb:
        err(path, f"is {mb:.1f} MB, over Play's {max_mb} MB limit")


def check_images(locale_dir):
    images = locale_dir / "images"
    if not images.is_dir():
        return
    if locale_dir.name != IMAGE_LOCALE:
        warn(images, f"graphics belong in {IMAGE_LOCALE} only; both stores fall back to it")
    for f in images.iterdir():
        if f.is_file() and f.stem not in ("icon", "featureGraphic", "promoGraphic", "tvBanner"):
            err(f, "unknown image; neither store will pick it up")
    icon = images / "icon.png"
    if icon.exists():
        check_image(icon, size=(512, 512), alpha=True, max_mb=1)
    for name, size in (("featureGraphic", (1024, 500)), ("promoGraphic", (180, 120)),
                       ("tvBanner", (1280, 720))):
        for f in images.glob(f"{name}.*"):
            check_image(f, size=size, alpha=False, max_mb=15)
    for d in SCREENSHOT_DIRS:
        shots = sorted(p for p in (images / d).glob("*") if p.is_file())
        if len(shots) > 8:
            err(images / d, f"{len(shots)} screenshots; Play takes at most 8")
        for s in shots:
            check_image(s, alpha=False, max_mb=8, screenshot=True)


def version_code():
    """The committed version's code, or None for a beta (it ships no What's New)."""
    def info(key):
        return subprocess.run(["bash", str(ROOT / "scripts/version_info.sh"), key],
                              check=True, capture_output=True, text=True).stdout.strip()
    return None if info("channel") == "beta" else int(info("version_code"))


def shipped_languages():
    langs = set()
    for d in RES.glob("values-*"):
        if (d / "strings.xml").exists():
            m = re.fullmatch(r"values-([a-z]{2,3})(?:-r([A-Z]{2}))?", d.name)
            if m:
                langs.add((m.group(1), m.group(2)))
    return langs


def main():
    complete = "--complete" in sys.argv[1:]
    current = version_code()
    locales = sorted(d for d in META.iterdir() if d.is_dir())
    names = {d.name for d in locales}

    for loc in REQUIRED_LOCALES:
        if loc not in names:
            err(META / loc, "missing; this locale is a store fallback and must exist")

    for d in locales:
        has_text = any((d / f).exists() for f in TEXT_LIMITS)
        for fname, limit in TEXT_LIMITS.items():
            f = d / fname
            if not f.exists():
                if has_text or d.name in REQUIRED_LOCALES:
                    err(f, "missing (a locale with any listing text needs all three files)")
                continue
            text = f.read_text(encoding="utf-8").strip()
            if not text:
                err(f, "empty")
            elif len(text) > limit:
                err(f, f"{len(text)} chars, limit {limit}")
            if fname == "title.txt" and TITLE_BANNED.search(text):
                warn(f, "promotional term in the title; Play policy forbids these")
        for f in sorted((d / "changelogs").glob("*.txt")):
            n = len(f.read_text(encoding="utf-8").strip())
            if f.stem == str(current) and n > CHANGELOG_LIMIT:
                err(f, f"{n} chars, limit {CHANGELOG_LIMIT}; Play would reject this release")
            elif n > CHANGELOG_LIMIT:
                warn(f, f"{n} chars, over {CHANGELOG_LIMIT} (already published)")
        check_images(d)

    for lang, region in sorted(shipped_languages(), key=str):
        match = f"{lang}-{region}" if region else lang
        if not any(n == match or n.startswith(f"{lang}-") for n in names):
            warn(META, f"app ships values-{lang}{'-r' + region if region else ''} "
                       "but there is no store locale for it")

    if complete:
        images = META / IMAGE_LOCALE / "images"
        if not (images / "icon.png").exists():
            err(images / "icon.png", "required for Play")
        if not list(images.glob("featureGraphic.*")):
            err(images / "featureGraphic.png", "required for Play (1024x500, no alpha)")
        if len(list((images / "phoneScreenshots").glob("*"))) < 2:
            err(images / "phoneScreenshots", "Play requires at least 2 phone screenshots")
        for loc in REQUIRED_LOCALES if current is not None else []:
            if not (META / loc / "changelogs" / f"{current}.txt").exists():
                err(META / loc / "changelogs" / f"{current}.txt", "no What's New for this version")

    for w in warnings:
        print(f"warning: {w}")
    for e in errors:
        print(f"ERROR: {e}", file=sys.stderr)
    if errors:
        return 1
    print(f"Store listing OK ({len(locales)} locales, {len(warnings)} warnings).")
    return 0


if __name__ == "__main__":
    sys.exit(main())

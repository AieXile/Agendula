#!/usr/bin/env bash
# Ensure the current version's "What's New" exists at
# fastlane/metadata/android/en-US/changelogs/<code>.txt, which both F-Droid and
# Google Play publish.
#
# A COMMITTED file wins and is never rewritten: it is a hand-written summary
# under 500 characters (Play's cap), not a copy of the CHANGELOG.md section.
# Only when the file is missing is it generated from that section, so the
# self-hosted release pipeline always has something to publish. The other
# locales' changelogs are written by hand and never touched here.
set -euo pipefail
cd "$(dirname "$0")/.."   # repo root

VERSION=$(bash scripts/version_info.sh version)
VERSION_CODE=$(bash scripts/version_info.sh version_code)

# Betas only ship to Codeberg, whose notes come from CHANGELOG.md. A What's New
# file for one would sit in the tree F-Droid and Play read, so none is wanted.
if [ "$(bash scripts/version_info.sh channel)" = beta ]; then
  echo "Beta $VERSION: no store What's New (betas only ship to Codeberg)."
  exit 0
fi

# Play rejects a longer "What's New" and F-Droid truncates it in-client.
MAX_CHARS=${MAX_CHARS:-500}

CL_DIR="fastlane/metadata/android/en-US/changelogs"
mkdir -p "$CL_DIR"
OUT="$CL_DIR/${VERSION_CODE}.txt"

if [ -s "$OUT" ]; then
  ACTION="Kept"
else
  ACTION="Generated"
  awk -v ver="$VERSION" '
    $0 ~ "^## \\[" ver "\\]" { flag = 1; next }
    /^## \[/ { flag = 0 }
    flag' CHANGELOG.md > "$OUT"
  # Trim leading blank lines (same as the pipeline did).
  sed -i -e '/./,$!d' "$OUT"
  [ -s "$OUT" ] || echo "See CHANGELOG.md for $VERSION." > "$OUT"
fi

# Counted like check_store_listing.py: surrounding whitespace doesn't count.
CHARS=$(python3 -c 'import sys; print(len(open(sys.argv[1], encoding="utf-8").read().strip()))' "$OUT")
echo "$ACTION $OUT (version $VERSION, code $VERSION_CODE, ${CHARS} chars)"

# Hard limit, enforced in CI (.forgejo/workflows/ci.yaml) and before a release.
if [ "$CHARS" -gt "$MAX_CHARS" ]; then
  cat >&2 <<MSG
ERROR: $OUT is $CHARS characters, over the $MAX_CHARS-character limit.

F-Droid truncates the in-client changelog, so this one would be cut off
mid-sentence and Play would reject the upload. Replace it with a hand-written
summary under $MAX_CHARS characters and commit it.
MSG
  exit 1
fi

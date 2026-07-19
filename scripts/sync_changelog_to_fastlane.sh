#!/usr/bin/env bash
# Write the current version's CHANGELOG.md section into the fastlane changelog
# file that F-Droid harvests: fastlane/metadata/android/en-US/changelogs/<code>.txt
# (en-US is F-Droid's fallback locale, so it covers every language).
#
# Run this when cutting a release (after editing CHANGELOG.md and bumping
# versionName in app/build.gradle.kts) and COMMIT the result, so the OFFICIAL
# F-Droid repo — which reads the changelog from the tagged source tree — shows
# this version's "What's New". The self-hosted release pipeline also runs it so
# its changelog never depends on the file having been committed. Idempotent.
#
# Extraction matches the awk used for the Gitea release notes so all three
# (release notes, self-hosted changelog, official changelog) stay in sync.
set -euo pipefail
cd "$(dirname "$0")/.."   # repo root

VERSION=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts)
[ -n "$VERSION" ] || { echo "No versionName in app/build.gradle.kts" >&2; exit 1; }
MAJOR=${VERSION%%.*}; rest=${VERSION#*.}; MINOR=${rest%%.*}; PATCH=${rest##*.}
MAJOR=${MAJOR:-0}; MINOR=${MINOR:-0}; PATCH=${PATCH:-0}
VERSION_CODE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))

CL_DIR="fastlane/metadata/android/en-US/changelogs"
mkdir -p "$CL_DIR"
OUT="$CL_DIR/${VERSION_CODE}.txt"

awk -v ver="$VERSION" '
  $0 ~ "^## \\[" ver "\\]" { flag = 1; next }
  /^## \[/ { flag = 0 }
  flag' CHANGELOG.md > "$OUT"
# Trim leading blank lines (same as the pipeline did).
sed -i -e '/./,$!d' "$OUT"
if [ ! -s "$OUT" ]; then
  echo "See CHANGELOG.md for $VERSION." > "$OUT"
fi

CHARS=$(wc -m < "$OUT" | tr -d ' ')
echo "Wrote $OUT (version $VERSION, code $VERSION_CODE, ${CHARS} chars)"
if [ "$CHARS" -gt 500 ]; then
  echo "  note: >500 chars — F-Droid may truncate this changelog in-client." >&2
fi

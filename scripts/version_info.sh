#!/usr/bin/env bash
# The one place that turns the committed versionName into a versionCode and a
# release channel. The release and beta pipelines, the changelog sync and the
# store-listing check all read it from here, so the scheme can't drift between
# them.
#
#   versionName           channel   versionCode
#   X.Y.Z (< 1.1.0)       stable    X*10000   + Y*100   + Z        (legacy)
#   X.Y.Z-beta.N          beta      X*1000000 + Y*10000 + Z*100 + N  (N = 1..98)
#   X.Y.Z (>= 1.1.0)      stable    X*1000000 + Y*10000 + Z*100 + 99
#
# A beta's code sits below its own stable release and above everything before
# it, so a beta install updates in place to the next beta and then to the
# stable version. Every 1.0.x keeps the legacy code it already shipped with;
# the first new-scheme code (1.1.0-beta.1 -> 1010001) is above all of them.
#
#   scripts/version_info.sh          print version, version_code, base_version,
#                                    channel and prerelease as key=value lines
#                                    (ready for $GITHUB_OUTPUT)
#   scripts/version_info.sh <key>    print just that value
#   scripts/version_info.sh --check  also fail unless the committed versionCode
#                                    matches; the official F-Droid repo builds
#                                    the tag as committed, so it must
set -euo pipefail
cd "$(dirname "$0")/.."   # repo root

GRADLE="app/build.gradle.kts"
NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$GRADLE" || true)
COMMITTED=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$GRADLE" || true)
[ -n "$NAME" ] || { echo "No versionName in $GRADLE" >&2; exit 1; }

if [[ ! "$NAME" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-beta\.([1-9][0-9]*))?$ ]]; then
  echo "versionName '$NAME' is neither X.Y.Z nor X.Y.Z-beta.N" >&2
  exit 1
fi
MAJOR=${BASH_REMATCH[1]}; MINOR=${BASH_REMATCH[2]}; PATCH=${BASH_REMATCH[3]}
BETA=${BASH_REMATCH[5]}
BASE="$MAJOR.$MINOR.$PATCH"

if [ "$MINOR" -gt 99 ] || [ "$PATCH" -gt 99 ]; then
  echo "versionName '$NAME': MINOR and PATCH each have room for 0-99" >&2
  exit 1
fi

LEGACY=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))
if [ -n "$BETA" ]; then
  if [ "$LEGACY" -lt 10100 ]; then
    echo "versionName '$NAME': betas start at 1.1.0; 1.0.x codes have no room for them" >&2
    exit 1
  fi
  if [ "$BETA" -lt 1 ] || [ "$BETA" -gt 98 ]; then
    echo "versionName '$NAME': beta number must be 1-98 (99 is the stable release)" >&2
    exit 1
  fi
  CHANNEL=beta
  CODE=$(( MAJOR * 1000000 + MINOR * 10000 + PATCH * 100 + BETA ))
elif [ "$LEGACY" -lt 10100 ]; then
  CHANNEL=stable
  CODE=$LEGACY
else
  CHANNEL=stable
  CODE=$(( MAJOR * 1000000 + MINOR * 10000 + PATCH * 100 + 99 ))
fi

# Betas are always pre-releases; so was everything before 1.0.0.
if [ "$CHANNEL" = beta ] || [ "$MAJOR" = 0 ]; then PRERELEASE=true; else PRERELEASE=false; fi

case "${1:-}" in
  "")
    printf 'version=%s\nversion_code=%s\nbase_version=%s\nchannel=%s\nprerelease=%s\n' \
      "$NAME" "$CODE" "$BASE" "$CHANNEL" "$PRERELEASE"
    ;;
  --check)
    if [ "$COMMITTED" != "$CODE" ]; then
      echo "ERROR: $GRADLE has versionCode = ${COMMITTED:-<none>}, but versionName '$NAME' needs $CODE." >&2
      echo "Set versionCode = $CODE (see docs/RELEASING.md for the scheme)." >&2
      exit 1
    fi
    echo "OK: versionName $NAME -> versionCode $CODE ($CHANNEL)."
    ;;
  version) echo "$NAME" ;;
  version_code) echo "$CODE" ;;
  base_version) echo "$BASE" ;;
  channel) echo "$CHANNEL" ;;
  prerelease) echo "$PRERELEASE" ;;
  *) echo "Unknown key '$1'" >&2; exit 2 ;;
esac

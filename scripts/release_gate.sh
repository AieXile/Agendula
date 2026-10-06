#!/usr/bin/env bash
# Decides whether the committed version still needs publishing; prints cut=true|false.
#
#   stable: no vX.Y.Z tag on Codeberg yet.
#   beta:   its Codeberg pre-release doesn't carry the APK yet, so a failed
#           publish is redone by the next push. Refused unless it is newer than
#           every shipped stable release.
#
# Fails on any lookup error rather than guessing.
set -euo pipefail
cd "$(dirname "$0")/.."   # repo root

REPO=${REPO:-jlmakiola/agendula}
VERSION=$(bash scripts/version_info.sh version)
BASE=$(bash scripts/version_info.sh base_version)
CHANNEL=$(bash scripts/version_info.sh channel)

# Exact tag names. The Codeberg git/refs/tags/<name> API matches by prefix.
TAGS=$(git ls-remote --tags --refs "https://codeberg.org/$REPO.git" | sed 's#.*refs/tags/##')

if [ "$CHANNEL" = stable ]; then
  if grep -qxF "v$VERSION" <<<"$TAGS"; then
    echo "Tag v$VERSION already exists on Codeberg — nothing to release." >&2
    echo "cut=false"
  else
    echo "No tag for v$VERSION on Codeberg yet — cutting the release." >&2
    echo "cut=true"
  fi
  exit 0
fi

LATEST=$(grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' <<<"$TAGS" | sed 's/^v//' | sort -V | tail -n1 || true)
if [ -n "$LATEST" ] && [ "$(printf '%s\n%s\n' "$BASE" "$LATEST" | sort -V | tail -n1)" = "$LATEST" ]; then
  echo "Beta $VERSION is not newer than the shipped stable $LATEST — bump the version." >&2
  exit 1
fi

ASSET="agendula_v${VERSION}.apk"
BODY=$(mktemp); trap 'rm -f "$BODY"' EXIT
STATUS=$(curl -s -o "$BODY" -w '%{http_code}' "https://codeberg.org/api/v1/repos/$REPO/releases/tags/v$VERSION" || echo 000)
case "$STATUS" in
  200)
    if grep -qF "\"name\":\"$ASSET\"" "$BODY"; then
      echo "Pre-release v$VERSION already carries $ASSET — nothing to do." >&2
      echo "cut=false"
    else
      echo "Pre-release v$VERSION exists without $ASSET — publishing it again." >&2
      echo "cut=true"
    fi
    ;;
  404)
    echo "No pre-release for v$VERSION yet — cutting the beta." >&2
    echo "cut=true"
    ;;
  *)
    echo "Codeberg release lookup for v$VERSION returned HTTP $STATUS — refusing to guess." >&2
    exit 1
    ;;
esac

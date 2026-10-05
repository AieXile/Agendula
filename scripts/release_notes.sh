#!/usr/bin/env bash
# Print the forge release notes for VERSION: its `## [VERSION]` section of
# CHANGELOG.md. A beta (X.Y.Z-beta.N) normally has no section of its own, so it
# falls back to `## [Unreleased]`, i.e. what the release branch carries so far,
# under a line saying what a beta is.
#
#   scripts/release_notes.sh 1.1.0 > release-notes.md
set -euo pipefail
cd "$(dirname "$0")/.."   # repo root

VERSION=${1:?usage: release_notes.sh VERSION}

section() {
  awk -v ver="$1" '
    $0 ~ "^## \\[" ver "\\]" { flag = 1; next }
    /^## \[/ { flag = 0 }
    flag' CHANGELOG.md | sed -e '/./,$!d'
}

NOTES=$(section "$VERSION")
if [[ "$VERSION" == *-beta.* ]]; then
  [ -n "$NOTES" ] || NOTES=$(section Unreleased)
  printf '%s\n\n' "**Beta of ${VERSION%%-*}, for testing.** It updates in place to later betas and to the stable release. Obtainium only offers betas with *Include prereleases* switched on; F-Droid and Play never get them."
fi
if [ -n "$NOTES" ]; then
  printf '%s\n' "$NOTES"
else
  echo "_No changelog entry for ${VERSION} — see CHANGELOG.md._"
fi

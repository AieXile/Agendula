#!/usr/bin/env bash
# Single source of truth: fastlane/metadata/android/<locale>/ feeds BOTH the
# official F-Droid repo (harvested from source automatically) and the
# self-hosted repo. This script transforms the fastlane layout into the F-Droid
# "localized" layout that the self-hosted `fdroid update` consumes, so we don't
# maintain two copies.
#
#   usage: fastlane_to_fdroid_localized.sh <fastlane_android_dir> <out_localized_dir>
#   e.g.   scripts/fastlane_to_fdroid_localized.sh \
#              fastlane/metadata/android \
#              fdroid/metadata/de.jeanlucmakiola.agendula
#
# Mapping (fastlane -> F-Droid repo localized):
#   short_description.txt        -> summary.txt
#   full_description.txt         -> description.txt
#   title.txt                    -> name.txt
#   images/{icon,featureGraphic,promoGraphic,tvBanner}.* -> same name
#   images/<type>Screenshots/*   -> <type>Screenshots/*
#   changelogs/<versionCode>.txt -> changelogs/<versionCode>.txt
# (changelogs are seeded into the fastlane tree by
#  scripts/sync_changelog_to_fastlane.sh.)
set -euo pipefail

SRC="${1:?need fastlane android dir, e.g. fastlane/metadata/android}"
OUT="${2:?need output localized dir, e.g. fdroid/metadata/<appid>}"

shopt -s nullglob
for locdir in "$SRC"/*/; do
  loc="$(basename "$locdir")"
  dst="$OUT/$loc"
  mkdir -p "$dst"
  [ -f "$locdir/short_description.txt" ] && cp "$locdir/short_description.txt" "$dst/summary.txt"
  [ -f "$locdir/full_description.txt"  ] && cp "$locdir/full_description.txt"  "$dst/description.txt"
  [ -f "$locdir/title.txt"             ] && cp "$locdir/title.txt"             "$dst/name.txt"
  for img in "$locdir"images/{icon,featureGraphic,promoGraphic,tvBanner}.*; do
    cp "$img" "$dst/"
  done
  for shots in "$locdir"images/*Screenshots/; do
    t="$(basename "$shots")"
    mkdir -p "$dst/$t"
    cp "$shots"* "$dst/$t/"
  done
  # Per-version changelogs live in the same fastlane tree (see
  # scripts/sync_changelog_to_fastlane.sh) and map straight across.
  if [ -d "$locdir/changelogs" ]; then
    mkdir -p "$dst/changelogs"
    cp "$locdir"changelogs/* "$dst/changelogs/"
  fi
done

echo "Built F-Droid localized metadata in '$OUT' from '$SRC'"

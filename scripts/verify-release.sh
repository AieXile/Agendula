#!/usr/bin/env bash
#
# Build the release-candidate APK and install it on a connected device for the
# mandatory pre-release on-device check (see docs/RELEASING.md).
#
# It builds the `releaseTest` variant: the same R8 shrinking + obfuscation and
# resource shrinking as the published `release` build, but debug-signed and
# with a `.releasetest` applicationId suffix so it installs alongside the
# production and debug apps. This is what surfaces release-only breakage (R8
# stripping) and first-run states (permission not yet granted) that the
# unminified debug build — or a device that already holds the permission —
# silently hides.
#
# Usage: scripts/verify-release.sh
set -euo pipefail

cd "$(dirname "$0")/.."

PKG="de.jeanlucmakiola.agendula.releasetest"
APK="app/build/outputs/apk/releaseTest/app-releaseTest.apk"

echo "==> Building release-candidate APK (releaseTest, R8 minified)…"
./gradlew :app:assembleReleaseTest

echo "==> Installing $PKG …"
adb install -r "$APK"

echo "==> Resetting to a first-run state (revoking tasks + notification permissions)…"
# Force the permission-not-granted state so the permission gate / onboarding is
# exercised every time — R8-only breakage and first-run crashes never show up in
# the unminified debug build, nor on a device that already holds the permission.
# Both tasks-provider permission sets are declared; revoke each so whichever the
# device's provider uses starts ungranted.
adb shell pm revoke "$PKG" org.dmfs.permission.READ_TASKS       2>/dev/null || true
adb shell pm revoke "$PKG" org.dmfs.permission.WRITE_TASKS      2>/dev/null || true
adb shell pm revoke "$PKG" org.tasks.permission.READ_TASKS      2>/dev/null || true
adb shell pm revoke "$PKG" org.tasks.permission.WRITE_TASKS     2>/dev/null || true
adb shell pm revoke "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

echo
echo "Installed and reset. Now verify ON THE DEVICE before releasing:"
echo "  1. Launch from a clean state — the permission screen must appear (no crash)."
echo "  2. Grant tasks access — the task list must load."
echo "  3. Create a task with a due reminder and confirm the notification fires."
echo "  4. Exercise the release's headline changes end to end."
echo
echo "Watch for crashes with:  adb logcat -b crash"
echo "Only merge the release branch to main once all of the above pass on a device"
echo "(the merge is what publishes the release — see docs/RELEASING.md)."

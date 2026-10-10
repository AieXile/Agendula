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
# Usage: scripts/verify-release.sh [full|offline]   (default: full)
set -euo pipefail

cd "$(dirname "$0")/.."

FLAVOR="${1:-full}"
case "$FLAVOR" in
  full) PKG="de.jeanlucmakiola.agendula.releasetest" ;;
  offline) PKG="de.jeanlucmakiola.agendula.offline.releasetest" ;;
  *) echo "Unknown flavor '$FLAVOR' (full|offline)." >&2; exit 2 ;;
esac
TASK="assemble${FLAVOR^}ReleaseTest"
APK="app/build/outputs/apk/$FLAVOR/releaseTest/app-$FLAVOR-releaseTest.apk"

echo "==> Building release-candidate APK ($FLAVOR releaseTest, R8 minified)…"
./gradlew ":app:$TASK"

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
echo "Installed and reset. Now verify ON THE DEVICE before releasing."
echo
echo "Which path you are on depends on what else is installed — since 1.0.0 the"
echo "app owns its store, so the default path grants nothing at all:"
echo
echo "  No OpenTasks / tasks.org on the device (the default, and what a fresh"
echo "  install looks like):"
echo "    1. Launch from a clean state — the task list must load straight away."
echo "       There is no permission to grant in this mode, so no gate appears."
echo "    2. Create a list, then a task in it — a fresh install has neither."
echo "    3. Give the task a due reminder and confirm the notification fires."
echo
echo "  With OpenTasks or tasks.org installed:"
echo "    1. Launch from a clean state — the permission gate must appear (no crash)."
echo "    2. Grant tasks access — that app's task list must load."
echo "    3. Settings -> Storage -> Copy tasks from ... — the confirm must name a"
echo "       real count, and switching to \"On this device\" must then show them."
echo "    4. Create a task with a due reminder and confirm the notification fires."
echo
echo "  Either way: exercise the release's headline changes end to end."
echo
echo "Watch for crashes with:  adb logcat -b crash"
echo "Only merge the release branch to main once all of the above pass on a device"
echo "(the merge is what publishes the release — see docs/RELEASING.md)."

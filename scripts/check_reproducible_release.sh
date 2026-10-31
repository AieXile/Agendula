#!/usr/bin/env bash
# Reproducibility guard: a rebuild of the published APK, from the same tag and
# on another machine, should produce the same bytes. Nothing here is about
# publishing to a store — it keeps the GitHub release verifiable.
#
# Each invariant guards against build metadata that would differ between two
# machines (learned on the sibling Calendula repository):
#   1. vcsInfo { include = false }          — else AGP embeds env-dependent git
#      metadata (META-INF/version-control-info.textproto) -> not reproducible.
#   2. no foojay toolchain resolver         — an offline rebuild cannot fetch a
#      JDK at build time, so a resolver would make the build machine-dependent.
#   3. dependenciesInfo { includeInApk = false } — else AGP embeds a "Dependency
#      metadata" block (id 0x504b4453) in the APK Signing Block, which differs
#      from a plain rebuild and is not needed by this fork's releases.
#   4. jniLibs { keepDebugSymbols += "**/*.so" } — else AGP strips prebuilt .so
#      files only when an NDK is installed, so the bytes depend on the host.
set -euo pipefail

APP="app/build.gradle.kts"
SETTINGS="settings.gradle.kts"
fail=0

# 1. AGP VCS-info must be disabled on the release build. -z reads the whole file
#    as one record so the match can span newlines; [^}] keeps it inside the block.
if grep -Pzoq 'vcsInfo\s*\{[^}]*include\s*=\s*false' "$APP"; then
  echo "OK: vcsInfo { include = false } — no env-dependent VCS metadata embedded."
else
  echo "ERROR: '$APP' release build is missing 'vcsInfo { include = false }'." >&2
  echo "       AGP would embed version-control-info.textproto, breaking reproducibility." >&2
  fail=1
fi

# 2. The foojay toolchain resolver must not be present in any Gradle script.
#    This includes the floret-kit submodule: it's an included build (composite
#    build via `includeBuild`), so its Gradle scripts run as part of this build
#    too, and the same bar applies to it.
gradle_files=("$SETTINGS" "$APP")
[ -f build.gradle.kts ] && gradle_files+=(build.gradle.kts)
if [ -d floret-kit ]; then
  while IFS= read -r f; do gradle_files+=("$f"); done \
    < <(find floret-kit -name '*.gradle.kts' -not -path '*/build/*')
fi
if grep -qi 'foojay' "${gradle_files[@]}"; then
  echo "ERROR: foojay toolchain resolver found in: $(grep -li foojay "${gradle_files[@]}" | tr '\n' ' ')" >&2
  echo "       F-Droid's source scanner rejects org.gradle.toolchains.foojay-resolver" >&2
  echo "       (it can fetch a JDK over the network). Remove the plugin." >&2
  fail=1
else
  echo "OK: no foojay toolchain resolver — offline build scanner stays happy."
fi

# 3. AGP dependency-metadata block must not be embedded in the APK.
if grep -Pzoq 'dependenciesInfo\s*\{[^}]*includeInApk\s*=\s*false' "$APP"; then
  echo "OK: dependenciesInfo { includeInApk = false } — no extra APK signing block."
else
  echo "ERROR: '$APP' is missing 'dependenciesInfo { includeInApk = false }'." >&2
  echo "       AGP would embed a 'Dependency metadata' block (0x504b4453) in the APK" >&2
  echo "       Signing Block, which F-Droid's binary scanner rejects." >&2
  fail=1
fi

# 4. Prebuilt native libs must not be stripped. AGP strips them only if an NDK is
#    installed, so the output would depend on the build host.
if grep -Pzoq 'jniLibs\s*\{[^}]*keepDebugSymbols\s*\+=\s*"\*\*/\*\.so"' "$APP"; then
  echo "OK: jniLibs keepDebugSymbols — native libs ship unstripped on every host."
else
  echo "ERROR: '$APP' is missing 'jniLibs { keepDebugSymbols += \"**/*.so\" }'." >&2
  echo "       AGP strips .so files only when an NDK is present, so a from-source" >&2
  echo "       rebuild would not match the published APK." >&2
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo >&2
  echo "Reproducible-release invariant(s) violated — official F-Droid publishing would" >&2
  echo "stall. Fix the above before merging." >&2
  exit 1
fi
echo "All reproducible-release invariants hold."

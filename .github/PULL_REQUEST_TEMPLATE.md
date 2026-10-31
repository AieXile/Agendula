<!--
Thanks for contributing to Agendula!

Please skim CONTRIBUTING.md if you haven't:
https://github.com/AieXile/Agendula/blob/main/CONTRIBUTING.md

Two things it's easy to get wrong:
  • The one architectural rule — provider column names, `TaskContract`,
    `ContentResolver` and the authority string never leak above `data/tasks/`.
  • Don't bump `versionName` / `versionCode`. That bump reaching `main` is what
    cuts a release, so it belongs only in a release PR.
-->

### What this changes


### Why

<!-- Closes #123 — link the issue this implements or fixes. -->


### How it was tested

<!--
Which of these ran green, and anything you exercised by hand. On-device notes
are especially useful for UI changes, and for anything touching the provider
read/write paths (OpenTasks / tasks.org installed).

  ./gradlew lintFullDebug lintOfflineDebug :app:testFullDebugUnitTest :app:testOfflineDebugUnitTest :app:assembleDebug
  python3 scripts/check_translations.py
-->


### Checklist

- [ ] `./gradlew lintFullDebug lintOfflineDebug :app:testFullDebugUnitTest :app:testOfflineDebugUnitTest :app:assembleDebug` passes locally
- [ ] New domain logic comes with JVM unit tests under `app/src/test/`
- [ ] Provider details stay inside `data/tasks/`
- [ ] `values-*/strings.xml` edits keep keys in step (`python3 scripts/check_translations.py`)
- [ ] `CHANGELOG.md` updated under `## [Unreleased]`, if the change is user-visible
- [ ] No `versionName` / `versionCode` bump
- [ ] No planning or design documents committed

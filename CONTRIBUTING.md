# Contributing to Agendula

Thanks for your interest in Agendula — a Material 3 Expressive task app with its
own Room task store and its own CalDAV sync, which can also work on top of the
OpenTasks `TaskContract` provider. This file covers the practical how.

## The one architectural rule

Everything above the data layer talks to `TasksRepository` and sees only domain
types and Flows. **Room entities, provider column names, `TaskContract`,
`ContentResolver` and the authority string never leak above `data/tasks/`.**
That seam is what let the store move from the provider to Room without touching
a screen, and what lets both stores sit behind one `TasksDataSource`. If a
change would expose storage details to a ViewModel or the UI, it's in the wrong
layer.

## Prerequisites

- JDK 17
- Android SDK: compileSdk 37, build-tools 36.0.0 (the Gradle wrapper handles AGP/Kotlin)
- Any device or emulator for the default path — the store is Agendula's own.
  Debug builds seed an "Agendula Demo" list via `DemoSeeder`. For External
  mode, one with **OpenTasks** or **tasks.org** installed; for sync, a CalDAV
  account (a local Radicale is the quickest).
- Clone with `--recurse-submodules`: the `floret-kit` component library is a
  submodule.
- The SDK is found through `sdk.dir` in `local.properties` or `ANDROID_HOME`.
  Set `ANDROID_HOME` rather than only `local.properties`: `floret-kit` is a
  composite build with its own project directory, so it cannot read this
  repository's `local.properties`.
- The Gradle daemon is pinned to **JetBrains Runtime 21** by
  `gradle/gradle-daemon-jvm.properties`, so Gradle downloads that JDK when no
  JVM is specified. Avoid the download by pointing Gradle at an installed JDK 21
  instead: `JAVA_HOME=/path/to/jdk-21 ./gradlew …`, or in Android Studio
  Settings → Build, Execution, Deployment → Build Tools → Gradle → **Gradle
  JDK** choose a 21 (not the bundled JBR). CI installs JBR 21 explicitly.

## Build, test, lint

```sh
./gradlew :app:assembleDebug            # build the debug APKs (full + offline)
./gradlew :app:testFullDebugUnitTest    # JVM unit tests (JUnit5 + Truth + Turbine)
./gradlew lintFullDebug lintOfflineDebug  # Android lint (CI runs this on every PR)
```

CI (`.github/workflows/ci.yml`) runs a reproducible-release invariant check,
then lint → unit tests → debug build on every pull request, so run these locally
before opening a PR. Keep CI green.

## Translations

Translations are plain resource files in this repository and are edited like any
other source: `app/src/main/res/values-*/strings.xml`. This fork has no Weblate
instance, so a pull request that fixes or adds a translation is normal work.

Adding a *new* English string to `values/strings.xml` is also normal PR work.
Partial translations are expected and fine — missing keys are informational.
Stale and orphaned keys are not, so run

```sh
python3 scripts/check_translations.py
```

before pushing. It reports those more clearly than lint's `MissingTranslation`
does, and it's what the `Translations` check runs on every PR.

A new language also needs one `<locale>` line in
`app/src/main/res/xml/locales_config.xml` — that file is the single source of
truth for both the in-app picker and the Android 13+ per-app language setting.

## Where to put code

| Layer | Lives in | Rule of thumb |
|---|---|---|
| Pure logic (models, filtering, sorting, form validation, date maths) | `domain/` | No Android imports — must be JVM-unit-testable. |
| Task storage | `data/tasks/` (`room/` for our own store) | The only place that knows about Room or the provider. New storage work goes through `TasksDataSource`, for both stores. |
| CalDAV sync | `data/sync/` | Accounts, the engine, scheduling and sync notices. |
| Reminders, prefs, DI, demo data | `data/reminders/`, `data/prefs/`, `data/di/`, `data/demo/` | |
| Screens | `ui/<area>/` | One ViewModel + immutable `UiState` per area; Compose for the screen. |

## Code style & conventions

- **Kotlin**, 4-space indent, LF line endings, final newline, no trailing
  whitespace — all enforced by `.editorconfig` (2-space for yaml/toml/json/md).
  Match the surrounding code.
- **Material 3 Expressive** for all UI, built from **floret-kit** components
  first (`CollapsingScaffold`, `GroupedRow`, `InlineTextField`,
  `FullScreenPicker` / `OptionPicker`, …) and colour-scheme tokens (never
  hardcoded colours). If a floret-kit component is nearly right, add the
  parameter there rather than dropping to raw Material 3.
- Prefer the domain layer for anything testable.

## Tests

- New domain logic (mappers, filters, sorting, forms, value mapping) **must**
  come with JVM unit tests under `app/src/test/`. The data source is the
  JVM-testable seam — mock or fake it rather than reaching for instrumentation.
- Add an instrumented test only when a path genuinely needs Android: the Room
  data source, migrations and the import paths live under `app/src/androidTest/`.

## Commits & PRs

- Write focused commits with clear messages (the existing history uses short,
  scoped subjects like `UI: ...` / `M1 ...`).
- Update [`CHANGELOG.md`](CHANGELOG.md) under `[Unreleased]` for any
  user-visible change — its sections become the GitHub release notes
  (see [`docs/RELEASING.md`](docs/RELEASING.md)).
- Don't bump `versionName` / `versionCode` in a regular PR — the committed
  `versionName` is bumped only when **cutting a release**; tagging that commit
  with `vX.Y.Z` is what publishes the APKs. See
  [`docs/RELEASING.md`](docs/RELEASING.md).

## Scope

Agendula stays on **open** standards: CalDAV and iCalendar, through its own
sync or through the OpenTasks provider (OpenTasks and tasks.org). Proprietary
backends (Google Tasks, Microsoft To Do) are out of scope by design. jtx's
richer contract is a possible later addition.

## License

By contributing you agree your contributions are licensed under the project's
[MIT License](LICENSE).

# Contributing to Agendula

Thanks for your interest in Agendula — a Material 3 Expressive task app that's a
pure front-end over the OpenTasks `TaskContract` provider, with no own database
or sync stack. Before diving in, skim [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
(how it's built), [`docs/ROADMAP.md`](docs/ROADMAP.md) (what's next), and
[`docs/PLAN.md`](docs/PLAN.md) (the design rationale). This file covers the
practical how.

## The one architectural rule

Everything above the data layer talks to `TasksRepository` and sees only domain
types and Flows. **Provider column names, `TaskContract`, `ContentResolver`, and
the authority string never leak above `data/tasks/`.** This is what keeps
"Posture B" (bundling the provider later) an additive change instead of a
rewrite — see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §7. If a change
would expose provider details to a ViewModel or the UI, it's in the wrong layer.

## Prerequisites

- JDK 17
- Android SDK: compileSdk 37, build-tools 36.0.0 (the Gradle wrapper handles AGP/Kotlin)
- A device or emulator with **OpenTasks** or **tasks.org** installed for
  anything touching the read/write paths (ideally with DAVx5 syncing a CalDAV
  task list, so there's real data). Debug builds fall back to `DemoSeeder` for
  sample data.

## Build, test, lint

```sh
./gradlew :app:assembleDebug        # build the debug APK
./gradlew :app:testDebugUnitTest    # JVM unit tests (JUnit5 + Truth + Turbine)
./gradlew lintDebug                 # Android lint (CI runs this on every PR)
```

CI (`.gitea/workflows/ci.yaml`) runs a reproducible-release invariant check,
then lint → unit tests → debug build on every pull request, so run these locally
before opening a PR. Keep CI green.

## Where to put code

| Layer | Lives in | Rule of thumb |
|---|---|---|
| Pure logic (models, filtering, sorting, form validation, date maths) | `domain/` | No Android imports — must be JVM-unit-testable. |
| Provider access | `data/tasks/` | The only place that knows about the provider. New provider work goes through `TasksDataSource`. |
| Reminders, prefs, DI, demo data | `data/reminders/`, `data/prefs/`, `data/di/`, `data/demo/` | |
| Screens | `ui/<area>/` | One ViewModel + immutable `UiState` per area; Compose for the screen. |

## Code style & conventions

- **Kotlin**, 4-space indent, LF line endings, final newline, no trailing
  whitespace — all enforced by `.editorconfig` (2-space for yaml/toml/json/md).
  Match the surrounding code.
- **Material 3 Expressive** for all UI: use `MaterialExpressiveTheme`, the
  colour-scheme tokens (never hardcoded colours), and canonical M3 components
  (e.g. `ListItem` for rows). Consult the `material-3` skill before designing a
  new screen or component.
- Prefer the domain layer for anything testable; keep `AndroidTasksDataSource`
  the only Android-coupled data implementation so the rest stays JVM-testable.

## Tests

- New domain logic (mappers, filters, sorting, forms, value mapping) **must**
  come with JVM unit tests under `app/src/test/`. The data source is the
  JVM-testable seam — mock or fake it rather than reaching for instrumentation.
- Add an instrumented test only when a path genuinely needs a real
  `ContentResolver`.

## Commits & PRs

- Write focused commits with clear messages (the existing history uses short,
  scoped subjects like `UI: ...` / `M1 ...`).
- Update [`CHANGELOG.md`](CHANGELOG.md) under `[Unreleased]` for any
  user-visible change — its sections feed the release notes and F-Droid "What's
  New" (see [`docs/RELEASING.md`](docs/RELEASING.md)).
- If your change shifts the architecture or completes a milestone, update
  [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) / [`docs/ROADMAP.md`](docs/ROADMAP.md)
  in the same PR.
- Don't bump `versionName` / `versionCode` in a regular PR — the committed
  `versionName` is bumped only when **cutting a release** (that bump reaching
  `main` is what triggers the release; the pipeline then mints the tag). See
  [`docs/RELEASING.md`](docs/RELEASING.md).

## Scope

Agendula stays true to its thesis: a front-end over **open** task backends
(CalDAV / iCalendar / DecSync via the OpenTasks provider). Proprietary backends
(Google Tasks, Microsoft To Do) are out of scope by design — they'd mean owning
a sync stack. v1 targets the OpenTasks contract (OpenTasks + tasks.org); jtx's
richer contract is a possible later addition.

## License

By contributing you agree your contributions are licensed under the project's
[MIT License](LICENSE).

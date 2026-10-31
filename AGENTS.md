# AGENTS.md

Working notes for anyone — human or AI — changing this repository. It describes
what the code is, where things belong and which rules are non-negotiable. The
process side lives in `CONTRIBUTING.md`; this file is the architecture and house
rules that a reviewer will hold a change to.

## What this repository is

A fork of [Agendula](https://codeberg.org/jlmakiola/agendula) (MIT), republished
under its own application id. See [`FORK.md`](FORK.md) for what the fork changed
and which licence obligations travel with it. The app is a task manager with its
own Room store, its own CalDAV sync and an OpenTasks-compatible provider.

Conversation with the maintainer happens in Chinese; **code, comments, commit
subjects and documentation are English**, matching the existing tree.

## Stack and modules

| Module | What it is | Rules |
|---|---|---|
| `:app` | The Android app: UI, DI, data sources, sync, widgets, reminders. | Android/Kotlin, Compose, Hilt, Room, DataStore, Glance, WorkManager. |
| `:caldav` | CalDAV protocol layer: discovery, auth, Nextcloud Login Flow v2. | Plain JVM module. No Android types. MIT. |
| `:dav` | Vendored [dav4jvm](https://github.com/bitfireAT/dav4jvm). | Plain JVM module. **MPL-2.0** — see *Licence boundary* below. |
| `floret-kit` | Submodule component library (composite build). | MIT. Consume it; don't vendor it into `:app` and don't edit it here. |

`:app` has two product flavours on the `network` dimension: `full` (CalDAV sync)
and `offline` (no sync, no network permission — enforced at build time by
`VerifyNoNetworkPermissions`). `releaseTest` is the debug-signed, R8-shrunk twin
of `release`, for smoke-testing a release candidate on a device.

Dependencies go through `gradle/libs.versions.toml`. Never hardcode a version in
a `build.gradle.kts`, and never add a dependency that duplicates something
`kotlinx`, AndroidX or `floret-kit` already provides.

## Architecture invariants

1. **The storage seam.** Everything above the data layer talks to
   `TasksRepository` and sees only domain types and Flows. Room entities, provider
   column names, `TaskContract`, `ContentResolver` and the authority string never
   leave `data/tasks/`. Both stores (Room and the OpenTasks provider) sit behind
   one `TasksDataSource`; new storage work goes there, not into a ViewModel.
2. **`domain/` is pure.** No Android imports — models, filtering, sorting, form
   validation, date maths, iCalendar mapping. It must be unit-testable on the JVM,
   and new logic there must come with tests under `app/src/test/`.
3. **One ViewModel + one immutable `UiState` per screen area**, `ui/<area>/`.
   Compose renders the state; it does not decide anything the domain could.
4. **DI is Hilt.** Add bindings to the modules under `data/di/`; don't reach for
   singletons, `Context` holders or service locators.
5. **Flavour differences are compile-time.** Use `BuildConfig.SYNC_ENABLED` (and
   flavour-scoped source sets) rather than runtime permission probing. The
   offline flavour must never gain a network permission, a sync dependency or a
   provider/sync resource.
6. **Widgets** live in `widget/` and are built with Glance, sharing components
   through `floret.glance`. They talk to the repository, never to Room directly.

## UI rules

- Material 3 Expressive, built from **floret-kit** components first
  (`CollapsingScaffold`, `GroupedRow`, `GroupedSectionHeader`, `GroupedSurface`,
  `InlineTextField`, `FullScreenPicker`, `OptionPicker`, `Position`, …). If a
  component is nearly right, add the parameter there — in its own repository —
  rather than dropping to raw Material 3 here and forking the look.
- Colours, shapes and type come from the theme and `colorScheme` tokens. No
  hardcoded colours, no ad-hoc corner radii.
- Screen chrome, spacing and list grouping should match the surrounding screens;
  a new screen must be indistinguishable in behaviour from the ones beside it.

## Translations

- Base strings: `app/src/main/res/values/strings.xml`. Translations: the matching
  `values-<locale>/strings.xml`, edited as normal source — there is no Weblate.
- Partial translations are fine; missing keys fall back to English. Stale or
  orphaned keys are not — `python3 scripts/check_translations.py` fails on them.
- Never translate a string marked `translatable="false"` (URLs, ids, tokens).
- A new language also needs one `<locale>` line in
  `app/src/main/res/xml/locales_config.xml`.

## Build, test, lint

```sh
./gradlew :app:assembleDebug                       # both flavours, debug
./gradlew lintFullDebug lintOfflineDebug           # Android lint (CI runs this)
./gradlew testFullDebugUnitTest testOfflineDebugUnitTest :dav:test :caldav:test
./gradlew assembleFullReleaseTest                  # R8 twin for scripts/check_r8_keeps.py
python3 scripts/check_translations.py
bash scripts/check_reproducible_release.sh
```

JDK 17 to compile, JDK 21 for the Gradle daemon (see
`gradle/gradle-daemon-jvm.properties`), Android SDK with `compileSdk 37` and
build-tools `36.0.0`. Clone with `--recurse-submodules`.

## CI and releases

- `.github/workflows/ci.yml` — the required `ci` check: the reproducibility
  invariant, version check, translation parity, then lint, unit tests, debug
  APKs and the R8 keep check. Docs-only PRs skip the Android build but still go
  green.
- `.github/workflows/translations.yml` — the required `Translations / check`.
- `.github/workflows/release.yml` — pushing a `vX.Y.Z` tag (or dispatching it)
  builds and signs the APKs and attaches them to a GitHub release. **One universal
  APK per flavour, no ABI splits.** GitHub releases are the only distribution
  channel: no store metadata, no fastlane.
- `versionName` and `versionCode` in `app/build.gradle.kts` are the source of
  truth. `versionCode` is derived from `versionName` by
  `scripts/version_info.sh`; `--check` fails when they disagree. Never bump either
  in a regular PR — only in the PR that cuts a release.
- User-visible changes go under `## [Unreleased]` in `CHANGELOG.md`; that section
  is what the release notes are rendered from.
- Full process: [`docs/RELEASING.md`](docs/RELEASING.md).

## Licence boundary

- Everything outside `dav/` is MIT. Keep the upstream copyright line in
  [`LICENSE`](LICENSE).
- `dav/` is **MPL-2.0**, file-level copyleft: don't move its code into `:app` or
  `:caldav`, don't relicense it, and keep its per-file headers. Modifications must
  stay available in source — this repository is that publication. Read
  [`dav/PROVENANCE.md`](dav/PROVENANCE.md) before touching it.
- Dependencies are Apache-2.0 / BSD-3-Clause. Their notices live in
  `ui/licences/OpenSourceLicences.kt`; keep that list current when you add one.
- Never copy code from another project without carrying its licence and
  attribution. (A sibling project of the maintainer's, AnimeTrack, is
  Apache-2.0: reuse is allowed, but a pasted block needs its notice and a note
  that it was modified.)

## Secrets

Never commit a keystore, `key.properties`, an API key or a client secret. Signing
material reaches CI only through repository secrets (`KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). The application id is
`com.aiexile.agendula`; changing it makes the app a new app to Android, with no
upgrade path.

## Working agreements

- One file, one responsibility. Split a feature across files rather than piling it
  into an existing one.
- Reuse before writing: search this repository, then `floret-kit`, then the
  version catalog, then the stdlib. Say why a new file or dependency is needed.
- For anything larger than a small fix, state the plan and wait for an answer
  before changing behaviour. A refactor must leave the UI and behaviour
  identical unless the maintainer asked for a change.
- Keep diffs focused. Don't fold unrelated cleanups, renames or formatting into a
  change.
- When a change touches user-facing text, permissions, distribution or licences,
  update the matching document: `README.md`, `docs/PRIVACY.md`,
  `docs/RELEASING.md`, `FORK.md` or `dav/PROVENANCE.md`.

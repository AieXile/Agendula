# This fork

Agendula is [upstream](https://codeberg.org/jlmakiola/agendula) by Jean-Luc
Makiola, released under the MIT licence. This fork keeps the app and its
behaviour, changes its identity so it can be published independently, and
removes the original author's release machinery. This page records what changed
and what the licences require of anyone redistributing it.

Because the application id changed, this fork installs alongside the upstream
app rather than replacing it, and it has no upgrade path from an upstream
install. Tasks can be moved between the two with
Settings → Backup & restore → Export / Import.

## What changed

**Identity**

- `applicationId` and `namespace`: `de.jeanlucmakiola.agendula` →
  `com.aiexile.agendula` (the `offline` flavour keeps its `.offline` suffix, and
  `releaseTest` its `.releasetest` suffix).
- Kotlin package `de.jeanlucmakiola.agendula` → `com.aiexile.agendula`, and
  `:caldav`'s `de.jeanlucmakiola.caldav` → `com.aiexile.caldav`. Source
  directories moved to match.
- The OpenTasks-compatible provider authority, the sync account type, the
  tasks-provider permission names and the `PRODID`/`UID` emitted in iCalendar
  data follow from the package, so they changed too.
- The app's display name is still **Agendula**; the author line in
  Settings → About now names the fork's maintainer.

**Links and in-app entry points**

- Source, licence, issue and privacy links point at this repository; the
  Weblate link points at `CONTRIBUTING.md` (translations are edited in the
  repository), and the Codeberg icon was replaced with a neutral one.
- The Ko-fi support row is hidden while `about_support_url` is empty, so the
  About card shows only what exists.

**Removed**

- `.gitea/` (the author's release and Renovate workflows, keyed to the
  author's accounts and signing secrets), `fdroid-metadata/`,
  `docs/fdroid-official/`, and the two publish scripts for the Codeberg and
  Gitea APIs, plus `scripts/release_gate.sh` (it queried the Codeberg release
  API for the upstream repository).
- All store publishing: `fastlane/` (Play metadata, `supply` lanes, changelogs),
  `Gemfile`, `scripts/check_store_listing.py`,
  `scripts/fastlane_to_fdroid_localized.sh` and
  `scripts/sync_changelog_to_fastlane.sh`. This fork publishes nothing but a
  GitHub release.
- Release notes, README badges and store text for channels this fork does not
  publish to. README screenshots moved from `fastlane/` to `assets/screenshots/`.

**CI and releases**

- The Forgejo workflow files were replaced with GitHub Actions:
  `.github/workflows/ci.yml` (the pull-request gate), `translations.yml` and
  `release.yml` (tag → signed APKs → GitHub release). The issue and pull-request
  templates moved from `.forgejo/` to `.github/` for the same reason, and
  `.forgejo/` is gone.
- A release is one universal APK per flavour — `full` and `offline` — with no
  ABI splits, and nothing uploads to a store.
- `AGENTS.md` records the architecture, the module and licence boundaries and the
  house rules; the upstream repository had no such file.

**Documentation**

- `README.md`, `README.zh.md`, `CONTRIBUTING.md`, `docs/PRIVACY.md`,
  `docs/RELEASING.md`, `CHANGELOG.md`'s intro and the issue/PR templates were
  rewritten for this fork. Historical `CHANGELOG.md` entries below `1.2.0`
  still describe upstream releases and were left intact.

**`dav/` licence headers**

- Three files (`Error.kt`, `PropStat.kt`, `Response.kt`) still carried a stale
  GPLv3 header upstream even though `dav/LICENSE` is MPL-2.0. They now carry the
  standard MPL-2.0 Exhibit A header; their code is unchanged. See
  [`dav/PROVENANCE.md`](dav/PROVENANCE.md), section *Licence headers*.

## Licence obligations

- **The app (everything outside `dav/`) is MIT.** [LICENSE](LICENSE) keeps the
  original copyright line and adds the fork's. Any redistribution — source or
  binary — must carry that notice and the licence text. Removing the upstream
  copyright line would be a licence violation, not a rebrand.
- **`dav/` is MPL-2.0** (a vendored copy of
  [dav4jvm](https://github.com/bitfireAT/dav4jvm)). MPL is file-level copyleft:
  files taken from there must stay MPL-2.0, their licence headers must be kept
  (§3.4), and the source of the MPL-covered files — including any modification —
  must be made available when a binary is distributed (§3.2). Keeping `dav/` in
  its own module and publishing this whole repository satisfies that. Do not
  move its code into the MIT part or relicense it.
- **`floret-kit`** (the submodule component library) is MIT, © Jean-Luc
  Makiola. Keep its attribution.
- **Third-party dependencies** (AndroidX, Compose, Room, Hilt, OkHttp, ical4j,
  …) are Apache-2.0 or BSD-3-Clause; their notices are listed in
  Settings → About → Open source licenses (`ui/licences/OpenSourceLicences.kt`).
  Keep that list current when a dependency changes.

## Defaults you will likely want to change

These are placeholders chosen to make the fork buildable, not finished branding:

| What | Where |
|---|---|
| App display name | `app/src/main/res/values/strings.xml` (`app_name`) |
| Launcher icon and README screenshots | `app/src/main/res/mipmap-*`, `assets/screenshots` |
| Repository, issue and privacy URLs | `app/src/main/res/values/strings.xml` (`about_source_url`, `report_issue_url`, `about_license_url`, `about_privacy_url`, `about_translate_url`) |
| Maintainer name and contact | `settings_about_author` and `settings_about_*` strings; `docs/PRIVACY.md` §1 |
| Application id | `app/build.gradle.kts` — changing it later makes the app a different app to Android, with no upgrade path |
| Submodule URL | `.gitmodules` (currently the upstream Codeberg repository) |

## Known follow-ups

- **Release secrets are not configured yet.** `.github/workflows/release.yml`
  publishes a signed build only when `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS` and `KEY_PASSWORD` exist as repository secrets — see
  [`docs/RELEASING.md`](docs/RELEASING.md).
- **GitHub releases are the only channel.** No store metadata, fastlane or
  service account ships with this fork; tagging `vX.Y.Z` publishes the APKs, and
  the release notes come from `CHANGELOG.md`.
- **`versionCode` derives from `versionName`** (`1.2.0` → `1020099`). A fresh
  application id starts from scratch; if you ever publish this fork's
  `com.aiexile.agendula` somewhere, every later build must keep the same
  signing key.

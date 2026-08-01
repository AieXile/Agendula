# Agendula — releasing

Agendula is distributed through a **self-hosted F-Droid repo** (on Hetzner) and a
**Codeberg release** per version carrying the signed APK as a direct download.
Both are produced automatically by `.gitea/workflows/release.yaml` when a
**bumped `versionName` reaches `main`** — the pipeline builds and publishes that
version, then creates the matching `vX.Y.Z` tag and the releases itself. The
parallel **Gitea release** is the changelog of record on the build instance and
carries no APK assets.

Codeberg is the canonical forge; Gitea is build infrastructure. See
[Two forges, one repo](#two-forges-one-repo) for how the two are wired.

While Agendula is pre-1.0 (`versionName` starts with `0.`), every release is
flagged as a **pre-release**. This happens automatically and graduates to a
stable release at `1.0.0` — no manual toggling.

---

## The source of truth: the committed version

A release is defined by the `versionName`/`versionCode` committed in
`app/build.gradle.kts` — **not** by a hand-pushed tag:

- `versionName` = `MAJOR.MINOR.PATCH` (e.g. `0.2.0`)
- `versionCode` = `MAJOR*10000 + MINOR*100 + PATCH` (`0.2.0` → `200`,
  `1.3.4` → `10304`)

So `MINOR` and `PATCH` each have room for 0–99. The release pipeline reads
`versionName`, pins `versionCode` to the derived value, builds, publishes, and —
once the APK is live — creates the tag `v<versionName>` at that commit. The tag
is an **output** of a successful release, not its trigger, so a tag always marks
a fully-shipped version (and a failure before publish leaves no tag, so
re-running the workflow safely retries).

---

## Cutting a release

1. **Assemble the release branch.** Create `release/vX.Y.Z` and merge the
   feature/fix branches for this release into it. Everything below happens on
   that branch, before it reaches `main`.
2. **Update `CHANGELOG.md`.** Move the `## [Unreleased]` items under a new
   `## [X.Y.Z]` heading (Keep a Changelog format). The text between that heading
   and the next `## [` becomes both the Gitea release notes and the F-Droid
   per-version "What's New". The heading **must** match the version exactly.
3. **Bump the committed `versionName`** (and `versionCode`) in
   `app/build.gradle.kts`. **This bump is what triggers the release** when the
   branch merges to `main`. Then run
   ```sh
   scripts/sync_changelog_to_fastlane.sh
   ```
   and commit the generated
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` — this is what
   makes the **official** F-Droid listing (which harvests the changelog from the
   tagged source tree) show this version. The self-hosted pipeline regenerates it
   regardless, so forgetting only affects the official listing.
4. **Verify the release build on a real device** — the mandatory gate:
   ```sh
   scripts/verify-release.sh
   ```
   It builds the `releaseTest` variant (same R8 config as `release`, debug-signed
   with a `.releasetest` suffix so it installs alongside the real app) and resets
   it to a first-run state. Then, on the device:
   - launch from a **clean / permission-not-granted** state — the permission
     screen must appear, no crash;
   - grant tasks access — the task list must load;
   - create a task with a due reminder and confirm the notification fires;
   - exercise this release's headline changes.

   Only proceed once all of that passes on-device.
5. **Merge `release/vX.Y.Z` into `main`.** That's it — no manual tagging. The
   merge triggers `release.yaml`, which detects the new version, builds, signs,
   publishes to F-Droid, and creates the `vX.Y.Z` tag + Gitea release.

> The `releaseTest` build type exists only for step 4 — it is never published.
> The pipeline always builds and signs the real `release` variant.

---

## What the pipeline does

CI and release are split so a change is built once on its PR and only does
release work when a merge actually cuts a release:

- **`ci.yaml`** (`.forgejo/workflows/`, on `pull_request`, **Codeberg**) — the
  reproducible-release invariant guard
  (`scripts/check_reproducible_release.sh`), then lint + unit tests + a debug
  assemble and a Trivy scan, once per PR. Docs/metadata-only PRs skip the Android
  build but still report a green `CI` check.
- **`translations.yaml`** (`.forgejo/workflows/`, on `pull_request`, **Codeberg**)
  — an SDK-free parity check (`scripts/check_translations.py`) over
  `values-*/strings.xml`, so Weblate PRs get fast feedback. Runs on every PR
  without a path filter, so the required `Translations / check` status is always
  reported.
- **`renovate.yml`** (`.gitea/workflows/`, Mondays 05:00 UTC plus
  `workflow_dispatch`, **Gitea**) — the dependency sweep. Runs the pinned
  Renovate image as its job container and targets Codeberg's API; the cadence
  lives here, not in `renovate.json5`, so the two don't double-gate.
- **`release.yaml`** (`.gitea/workflows/`, on push to `main` plus
  `workflow_dispatch`, **Gitea**) — a cheap `detect` job reads `versionName` and
  checks **Codeberg** for a tag for it. Only when there isn't one does the
  `release` job run: unit tests on the merged commit, pin `versionCode`, build &
  sign the release APK with the **app key**, copy it into the F-Droid repo,
  generate the per-version changelog from the fastlane tree, re-sign the index
  with the **repo key**, upload `repo/` + `metadata/`, then create the `vX.Y.Z`
  tag + Gitea release (CHANGELOG section as notes, flagged pre-release while
  `MAJOR` is 0), attach the R8 `mapping.txt`, and publish the release on
  **Codeberg** with the signed APK + a SHA-256 checksum. Ordinary merges with no
  version bump fall through `detect` and do nothing.

### Codeberg direct-download channel

Alongside F-Droid, each release is published on the Codeberg repo
(`jlmakiola/agendula`) as a plain download for users who don't want F-Droid.
Releases aren't git objects and don't sync with the push mirror in either
direction, so the pipeline pushes the `vX.Y.Z` tag straight to Codeberg, creates
the release over the Codeberg API, and attaches `agendula_v<version>.apk` + its
`.sha256`. It's the same APK the F-Droid repo serves (same **app key**), so it
adds no trust surface. It skips cleanly if `CODEBERG_RELEASE_TOKEN` is unset,
but it is **not** `continue-on-error`: through 0.2.1–0.3.2 this step reported
green while never once publishing, which is how a crash-fix release reached
F-Droid but not the Codeberg/Obtainium users who needed it. A broken mirror
fails the release loudly.

One-time setup: the Codeberg repo's **Releases** unit must be enabled and a
`CODEBERG_RELEASE_TOKEN` secret (Codeberg access token, `write:repository` scope
— it pushes the tag as well as creating the release) added to Gitea Actions.

### Manual re-sign / recovery

A manual `workflow_dispatch` of the release workflow runs a **re-sign-only**
path: `detect` reports it's not a release, so the `release` job skips the APK
build, the version pin, and tag/release creation, and just re-signs the existing
F-Droid index with the configured repo key and re-uploads. Use this for key
rotation or repo recovery without publishing a new app version.

---

## Two forges, one repo

**Codeberg (`jlmakiola/agendula`) is canonical** — git, issues, PRs, tags and
releases. The self-hosted Gitea instance is build infrastructure: it holds the
signing key, publishes the F-Droid repo, and runs the release pipeline. Codeberg
push-mirrors `main` and tags to Gitea, and a bumped `versionName` arriving there
triggers `release.yaml` exactly as before.

Workflows are separated by **directory**, not by conditionals. Forgejo looks in
`.forgejo/workflows` → `.gitea/workflows` → `.github/workflows` and stops at the
first that exists; Gitea doesn't know `.forgejo/` at all:

| Directory | Runs on | Contains | Secrets |
| --- | --- | --- | --- |
| `.forgejo/workflows/` | Codeberg | `ci.yaml`, `translations.yaml` | **none** |
| `.gitea/workflows/` | Gitea | `release.yaml`, `renovate.yml` | signing key, F-Droid, Hetzner, bot tokens |

The line is drawn at **secrets, not at CI-vs-release**. That's what makes fork
PRs safe: everything a contributor can trigger lives in `.forgejo/` and can
reference no secret. Renovate stays on the Gitea runner *even though it opens
PRs on Codeberg* — it talks to Codeberg's API rather than moving its token onto
the contributor-facing runner. `detect` (and the Renovate job) additionally
carries a
`github.repository_owner == 'makiolaj'` guard, because the directory split only
holds while `.forgejo/` is non-empty — empty it and Codeberg would fall back to
`.gitea/` and run the release pipeline on the contributor-facing runner.

Two consequences worth remembering:

- **`detect` reads tags from Codeberg**, not from the Gitea instance it runs on.
  Push mirroring is `git push --mirror`, so a tag minted on Gitea is deleted by
  the next sync until the Codeberg tag push propagates back. Asking Gitea inside
  that window would re-cut a shipped release.
- **Any ref that exists only on Gitea gets deleted** by the mirror. That's
  correct under Codeberg-canonical, but don't debug a "vanished" branch without
  remembering it.

`floret-kit` is a submodule of this repo and follows the same move: `.gitmodules`
points at `https://codeberg.org/jlmakiola/floret-kit.git`, so a clone resolves
without reaching the personal Gitea instance. The Gitea copy is **kept** — every
existing tag records the old submodule URL, so rebuilds of past releases still
resolve.

---

## Secrets (Gitea → repo Settings → Actions → Secrets)

The workflow fails loudly if the F-Droid ones are missing — it will **never**
auto-generate a repo key (that would rotate the repo fingerprint and break every
user's pinned repo).

| Secret | Purpose |
| --- | --- |
| `KEYSTORE_BASE64`, `KEY_PASSWORD`, `KEY_ALIAS` | **App** signing key — signs the APK. Losing it means existing installs can't be updated. |
| `FDROID_KEYSTORE_BASE64` | **F-Droid repo** signing key (`keystore.p12`, base64). Signs the repo index. |
| `FDROID_CONFIG_BASE64` | F-Droid `config.yml` (base64) — repo metadata + keystore passwords. |
| `HETZNER_HOST`, `HETZNER_USER`, `HETZNER_PASS` | Upload target for the F-Droid repo. |
| `GITHUB_TOKEN` | Provided by Gitea Actions; used to create the release + attach assets. |
| `CODEBERG_RELEASE_TOKEN` | Codeberg access token (`write:repository` scope) — pushes the tag to Codeberg, creates the release there and uploads the APK/checksum. If unset the step skips; if set and failing, the release fails. |
| `RENOVATE_TOKEN` | Codeberg bot-account token — repo read/write + PR scope on `jlmakiola/agendula`. Used only by `renovate.yml`. |
| `GITHUB_COM_TOKEN` | Read-only github.com PAT (no scopes). Without it Renovate's changelog lookups hit the 60/h anonymous rate limit and PRs arrive with empty release notes. |

The app key signs APKs; the repo key signs the index (its fingerprint is what
users pin). Neither key nor `config.yml` is ever uploaded to the server — they
live only in CI secrets and are reconstructed in-runner (nginx serves only
`repo/`).

---

## F-Droid metadata (single source of truth)

Store-listing text lives in **`fastlane/metadata/android/<locale>/`** — the same
tree the official F-Droid repo harvests from source. At release time
`scripts/fastlane_to_fdroid_localized.sh` transforms it into the F-Droid repo's
"localized" layout, so there is no second copy to maintain. The app-level control
file (`Categories`/`License`/links) stays in
`fdroid-metadata/de.jeanlucmakiola.agendula.yml`. Per-version changelogs are
seeded into `fastlane/.../en-US/changelogs/<versionCode>.txt` by
`scripts/sync_changelog_to_fastlane.sh` (step 3 above) and carried across by the
transform.

---

## Crash deobfuscation

Each release attaches `mapping-<version>.txt.gz` (the R8 mapping) to its Gitea
release. To deobfuscate a user stacktrace, download the mapping for that version
and run it through `retrace`.

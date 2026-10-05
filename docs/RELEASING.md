# Agendula — releasing

Agendula is distributed through a **self-hosted F-Droid repo** (on Hetzner), a
**Codeberg release** per version carrying the signed APK as a direct download,
and **Google Play**. All three are produced automatically by `.gitea/workflows/release.yaml` when a
**bumped `versionName` reaches `main`** — the pipeline builds and publishes that
version, then creates the matching `vX.Y.Z` tag and the releases itself. The
parallel **Gitea release** is the changelog of record on the build instance and
carries no APK assets.

Codeberg is the canonical forge; Gitea is build infrastructure. See
[Two forges, one repo](#two-forges-one-repo) for how the two are wired.

Before a stable release you can ship **betas** (`X.Y.Z-beta.N`) from the
release branch. They go to **Codeberg only**, flagged as pre-releases; F-Droid
and Play never see them. See [Cutting a beta](#cutting-a-beta).

While Agendula was pre-1.0 (`versionName` started with `0.`), every release was
flagged as a **pre-release** automatically.

---

## The source of truth: the committed version

A release is defined by the `versionName`/`versionCode` committed in
`app/build.gradle.kts` — **not** by a hand-pushed tag:

- `versionName` = `MAJOR.MINOR.PATCH` (e.g. `1.1.0`), or
  `MAJOR.MINOR.PATCH-beta.N` for a beta (e.g. `1.1.0-beta.2`)
- `versionCode` is derived from it by `scripts/version_info.sh`:

| `versionName` | `versionCode` | Example |
| --- | --- | --- |
| `X.Y.Z` below 1.1.0 (legacy) | `X*10000 + Y*100 + Z` | `1.0.1` → `10001` |
| `X.Y.Z-beta.N` (N = 1–98) | `X*1000000 + Y*10000 + Z*100 + N` | `1.1.0-beta.2` → `1010002` |
| `X.Y.Z` from 1.1.0 | `X*1000000 + Y*10000 + Z*100 + 99` | `1.1.0` → `1010099` |

A beta's code sits below its own stable release and above everything before
it, so a beta install updates in place to the next beta and then to the stable
version. Every 1.0.x keeps the code it already has, and `1.1.0-beta.1`
(`1010001`) is above all of them. `MINOR` and `PATCH` each have room for 0–99.
Run `scripts/version_info.sh` to see what the committed version resolves to;
CI fails a PR whose committed `versionCode` doesn't match, because the official
F-Droid repo builds the tag exactly as committed.

The release pipeline reads `versionName`, pins `versionCode` to the derived
value, builds, publishes, and — once the APK is live — creates the tag
`v<versionName>` at that commit. The tag is an **output** of a successful
release, not its trigger, so a tag always marks a fully-shipped version (and a
failure before publish leaves no tag, so re-running the workflow safely
retries).

---

## Cutting a release

1. **Assemble the release branch.** Create `release/vX.Y.Z` and merge the
   feature/fix branches for this release into it. Everything below happens on
   that branch, before it reaches `main`.
2. **Update `CHANGELOG.md`.** Move the `## [Unreleased]` items under a new
   `## [X.Y.Z]` heading (Keep a Changelog format). The text between that heading
   and the next `## [` becomes the Gitea and Codeberg release notes. The
   heading **must** match the version exactly.
3. **Bump the committed `versionName`** (and `versionCode`, which
   `scripts/version_info.sh` prints) in `app/build.gradle.kts`. **This bump is what triggers the release** when the
   branch merges to `main`.
   Then write this version's **"What's New"** by hand:
   `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`, one per
   shipped locale, each a short summary **under 500 characters** — F-Droid and
   Play both publish these files, and Play rejects a longer one. It is not a
   copy of the CHANGELOG.md section. `scripts/sync_changelog_to_fastlane.sh`
   keeps a committed `en-US` file as it is and only generates one from
   CHANGELOG.md when it is missing; CI fails a PR whose version has no
   committed `en-US` changelog.
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
   publishes to F-Droid, creates the `vX.Y.Z` tag + releases, and uploads the
   App Bundle to Play.

> The `releaseTest` build type exists only for step 4 — it is never published.
> The pipeline always builds and signs the real `release` variant.

---

## Cutting a beta

A beta is a test build of the next version, published as a **pre-release on
Codeberg** and nowhere else. It is signed with the real app key, so testers
install it over their stable install and it updates in place to later betas
and to the stable release.

1. **On `release/vX.Y.Z`**, with the features merged in, set
   `versionName = "X.Y.Z-beta.1"` and the matching `versionCode`
   (`scripts/version_info.sh` prints it; `1.1.0-beta.1` → `1010001`).
   No What's New file — betas don't ship to the stores. Notes come from a
   `## [X.Y.Z-beta.N]` section of `CHANGELOG.md` if you write one, otherwise
   from `## [Unreleased]`.
2. **Optionally verify it on a device** with `scripts/verify-release.sh`, as for
   a stable release.
3. **Push the branch to Codeberg.** When it reaches Gitea, `beta.yaml` sees a
   beta version without a tag, runs the unit tests, builds and signs the APK,
   creates the `vX.Y.Z-beta.1` tag + a Gitea pre-release (with the R8 mapping)
   and publishes the **Codeberg pre-release** with the APK + `.sha256`.
4. **Next round:** bump to `-beta.2` (and its `versionCode`) and push again.
5. **Going stable:** set `versionName = "X.Y.Z"` and its `versionCode`
   (`1.1.0` → `1010099`), then continue from step 2 of
   [Cutting a release](#cutting-a-release).

Who gets a beta:

- **Obtainium** users only with *Include prereleases* switched on for the app.
  That is how a tester opts in; everyone else stays on stable.
- **F-Droid** (self-hosted and official) never: `beta.yaml` doesn't touch the
  self-hosted repo, and the official recipe's `UpdateCheckMode: Tags ^v[0-9.]+$`
  ignores `-beta` tags.
- **Play** never.

Guards: a beta version can't reach `main` (CI fails the PR, and `release.yaml`'s
`detect` refuses one as a backstop), `beta.yaml` refuses a beta of a version
that already shipped stable, and betas start at 1.1.0 (the 1.0.x codes have no
room below them).

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
- **`renovate.yml`** (`.gitea/workflows/`, Mondays 05:00 UTC, on push to
  `main`, plus `workflow_dispatch`, **Gitea**) — the dependency sweep. Runs the
  pinned Renovate image as its job container and targets Codeberg's API. New
  PRs only open in the Monday `schedule` window in `renovate.json5` (keep it
  bracketing the cron); the on-merge runs fall outside it and only rebase
  existing PRs that the merge left conflicted.
- **`release.yaml`** (`.gitea/workflows/`, on push to `main` plus
  `workflow_dispatch`, **Gitea**) — a cheap `detect` job reads `versionName` and
  checks **Codeberg** for a tag for it. Only when there isn't one does the
  `release` job run: unit tests on the merged commit, pin `versionCode`, build &
  sign the release APK with the **app key**, copy it into the F-Droid repo,
  generate the per-version changelog from the fastlane tree, re-sign the index
  with the **repo key**, upload `repo/` + `metadata/`, then create the `vX.Y.Z`
  tag + Gitea release (CHANGELOG section as notes, flagged pre-release while
  `MAJOR` is 0), attach the R8 `mapping.txt`, and publish the release on
  **Codeberg** with the signed APK + a SHA-256 checksum, and finally builds the
  App Bundle. Ordinary merges with no version bump fall through `detect` and do
  nothing.
- **`beta.yaml`** (`.gitea/workflows/`, on push to `release/**`, **Gitea**) —
  the same `detect` idea for betas: only when the committed `versionName` is
  `X.Y.Z-beta.N` and Codeberg has no tag for it does the `beta` job run: unit
  tests, pin `versionCode`, build & sign the release APK with the **app key**,
  create the tag + a Gitea pre-release with the R8 mapping, and publish the
  **Codeberg pre-release** with the APK + SHA-256. No F-Droid, no Play.
- **`play` job** (same workflow, after `release`) — uploads the App Bundle and
  every locale's "What's New" to Google Play. Runs last and separately so a Play
  rejection can't endanger a release that already shipped; skips cleanly until
  Play is configured.

### Codeberg direct-download channel

Alongside F-Droid, each release is published on the Codeberg repo
(`jlmakiola/agendula`) as a plain download for users who don't want F-Droid.
Releases aren't git objects and don't sync with the push mirror in either
direction, so the pipeline pushes the `vX.Y.Z` tag straight to Codeberg, creates
the release over the Codeberg API, and attaches `agendula_v<version>.apk` + its
`.sha256`. It's the same APK the F-Droid repo serves (same **app key**), so it
adds no trust surface. The logic lives in `scripts/publish_codeberg_release.sh`,
which `beta.yaml` uses too (with the pre-release flag set). It skips cleanly if `CODEBERG_RELEASE_TOKEN` is unset,
but it is **not** `continue-on-error`: through 0.2.1–0.3.2 this step reported
green while never once publishing, which is how a crash-fix release reached
F-Droid but not the Codeberg/Obtainium users who needed it. A broken mirror
fails the release loudly.

### Google Play channel

**Artifact.** Play gets an **App Bundle** (`bundleRelease`), never the APK. It is
built from the same source and signing config at the very end of the `release`
job, `continue-on-error`, and handed to the `play` job as a workflow artifact
(via a commit-pinned fork of `upload-artifact`/`download-artifact`; the official
v4 actions refuse any non-github.com forge). The APK and the F-Droid
reproducibility guarantee are untouched.

**Signature.** Play App Signing treats the app key as the **upload key** only
and re-signs with Google's key. A Play install and an F-Droid/Codeberg install
therefore have **different signatures** and cannot update each other; switching
channels means uninstalling. Losing the app key is recoverable for Play (upload
key reset) but not for F-Droid.

**fastlane** is only the Play Developer API client (`supply`), never the build.
The `deploy` lane uploads the bundle plus `changelogs/<versionCode>.txt` for
every locale that has one. It never touches the listing; that is the `listing`
lane's job (see [Store listings](#store-listings-single-source-of-truth)).

**Track.** Uploads go straight to `production` at full rollout — the release
branch review is already the gate. Set the `PLAY_TRACK` variable (e.g.
`internal`) to stage, or `PLAY_RELEASE_STATUS=draft` to hold it unpublished.

**First-time setup.**
1. Create the app in the Play Console with **English (United States) – en-US**
   as the default language, so Play and F-Droid fall back to the same locale.
2. Upload the first AAB **by hand** — the API can't create an app's first
   release. Enrol in Play App Signing on the way.
3. Create a Google Cloud service account, grant it release permissions for the
   app in the Play Console, and store its JSON key as
   `PLAY_SERVICE_ACCOUNT_JSON`.
4. Push the listing once with the `listing` lane (see
   [Store listings](#store-listings-single-source-of-truth)), so every locale
   that has a changelog also exists on Play before `deploy` sends its
   "What's New".
5. Rehearse with `PLAY_DRY_RUN=true`, then clear it.

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
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Cloud service-account key (full JSON) with Play Console release access. Uploads the AAB. If unset, the `play` job skips cleanly. |
| `RENOVATE_TOKEN` | Codeberg bot-account token — repo read/write + PR scope on `jlmakiola/agendula`. Used only by `renovate.yml`. |
| `GITHUB_COM_TOKEN` | Read-only github.com PAT (no scopes). Without it Renovate's changelog lookups hit the 60/h anonymous rate limit and PRs arrive with empty release notes. |

The app key signs APKs; the repo key signs the index (its fingerprint is what
users pin). Neither key nor `config.yml` is ever uploaded to the server — they
live only in CI secrets and are reconstructed in-runner (nginx serves only
`repo/`).

### Variables (Gitea → repo Settings → Actions → Variables)

| Variable | Default | Purpose |
| --- | --- | --- |
| `PLAY_TRACK` | `production` | Play track for the bundle; `internal`/`alpha`/`beta` to stage. |
| `PLAY_RELEASE_STATUS` | `completed` | `completed`, `draft`, `inProgress` or `halted`. |
| `PLAY_DRY_RUN` | `false` | `true` validates the Play edit and discards it. |

---

## Store listings (single source of truth)

Everything both stores show — title, short and full description, "What's New",
icon, feature graphic, screenshots — lives in
**`fastlane/metadata/android/<locale>/`**. Nothing is edited in the Play Console
or kept in a second copy for F-Droid.

```
fastlane/metadata/android/
  en-US/                      fallback of both stores; holds every graphic
    title.txt                 ≤ 30 chars
    short_description.txt     ≤ 80 chars
    full_description.txt      ≤ 4000 chars
    changelogs/<code>.txt     ≤ 500 chars
    images/
      icon.png                512×512, 32-bit PNG
      featureGraphic.png      1024×500, no alpha
      phoneScreenshots/       2–8, no alpha, 320–3840 px, long side ≤ 2× short
      sevenInchScreenshots/   optional, same rules
      tenInchScreenshots/     optional, same rules
  en-GB/, de-DE/, pt-BR/, …   text only; one per shipped values-* language
```

`en-US` is the fallback for both stores: F-Droid always uses it, and it is the
Play app's default language. So text and graphics committed there cover every
locale without its own. Graphics are committed **once, under `en-US`**; add them
to another locale only when they actually differ (e.g. localized screenshots).

A raw phone capture (1080×2400, 20:9) breaks Play's 2:1 aspect cap. Frame or
crop screenshots to at most 2:1, e.g. 1080×2160.

**Validation.** `scripts/check_store_listing.py` checks the whole tree against
Play's limits (the stricter of the two stores) and runs on every PR. Add
`--complete` to also require what a live Play listing needs: icon, feature
graphic, ≥ 2 phone screenshots and this version's `en-US` changelog.

**F-Droid.** The official repo harvests the tree from the tagged source. The
self-hosted repo gets it through `scripts/fastlane_to_fdroid_localized.sh` at
release time. The app-level control file (`Categories`/`License`/links) stays in
`fdroid-metadata/de.jeanlucmakiola.agendula.yml`.

**Play.** `fastlane/Fastfile`'s `listing` lane pushes text and graphics
(`supply`, only changed images are re-uploaded). It is run by hand, never by the
release pipeline, because overwriting a live listing triggers a Play review:

```sh
bundle install
SUPPLY_JSON_KEY=/path/to/play-service-account.json \
  bundle exec fastlane listing dry_run:true   # validate, discard
SUPPLY_JSON_KEY=/path/to/play-service-account.json \
  bundle exec fastlane listing
```

The lane refuses to run unless `check_store_listing.py --complete` passes.
If the Play app's default language is ever not `en-US`, set
`PLAY_DEFAULT_LOCALE` and the lane copies the graphics into that locale.

## Crash deobfuscation

Each release attaches `mapping-<version>.txt.gz` (the R8 mapping) to its Gitea
release. To deobfuscate a user stacktrace, download the mapping for that version
and run it through `retrace`.

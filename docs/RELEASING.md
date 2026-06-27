# Agendula — releasing

Agendula is distributed through a **self-hosted F-Droid repo** (on Hetzner) with a
human-readable **Gitea release** per tag. Both are produced automatically by
`.gitea/workflows/release.yaml` when you push a tag. There are no APK assets on
the Gitea release itself — distribution lives in the F-Droid repo; the release is
the changelog of record.

---

## The one source of truth: the git tag

The git tag drives the version. You do **not** hand-edit version numbers for a
release — CI substitutes them from the tag:

- `versionName` = the tag without a leading `v` (e.g. `v0.2.0` → `0.2.0`).
- `versionCode` = `MAJOR*10000 + MINOR*100 + PATCH` (e.g. `0.2.0` → `200`,
  `1.3.4` → `10304`).

The values committed in `app/build.gradle.kts` are just the local/dev default;
keep them roughly matching the latest released tag, but the tag wins at release
time.

---

## Cutting a release

1. **Update `CHANGELOG.md`.** Move items out of `[Unreleased]` into a new
   `## [X.Y.Z]` section. The release pipeline extracts everything between
   `## [X.Y.Z]` and the next `## [` heading and uses it verbatim as both the
   Gitea release notes and the F-Droid per-version "What's New"
   (`changelogs/<versionCode>.txt`). If no matching section exists, a fallback
   line is used — so the heading **must** match the tag's version exactly.
2. **Commit** the changelog on `main`.
3. **Tag and push:**
   ```sh
   git tag v0.2.0
   git push origin v0.2.0
   ```
4. CI takes over (see below). Watch the run in Gitea Actions.

---

## What CI does on a tag

`release.yaml` runs three jobs:

| Job | Purpose |
|---|---|
| `ci` | Sanity gate: unit tests + a debug build (catches version-substitution drift). The other jobs depend on this. |
| `build-and-deploy` | Substitute version from the tag → build signed release APK → fetch the existing F-Droid repo from Hetzner → add the new APK + per-version changelog → `fdroid update -c` → upload `repo/` + `metadata/` back. Also attaches the R8 `mapping.txt.gz` to the Gitea release (best-effort) so crash stacktraces stay deobfuscatable. |
| `gitea-release` | Create/update the Gitea release for the tag, body = the extracted CHANGELOG section. Gated on `ci` only (not deploy), so notes still publish if the F-Droid upload hiccups. |

Both deploy and release steps are **re-run safe** (idempotent upserts), so a
failed run can be retried.

---

## Required CI secrets

Configured in the Gitea repo settings; the workflow fails loudly if the F-Droid
ones are missing (it will **never** auto-generate a repo key — that would rotate
the repo fingerprint and break every user's pinned repo).

| Secret | Used for |
|---|---|
| `KEYSTORE_BASE64`, `KEY_PASSWORD`, `KEY_ALIAS` | App signing keystore (the APK). |
| `FDROID_KEYSTORE_BASE64`, `FDROID_CONFIG_BASE64` | The F-Droid **repo** signing key + `config.yml`. Never uploaded to the server. |
| `HETZNER_HOST`, `HETZNER_USER`, `HETZNER_PASS` | SFTP target for the published `repo/` + `metadata/`. |
| `GITHUB_TOKEN` | Gitea API (release create/patch, asset upload). |

The repo signing key and `config.yml` come from secrets at build time and are
**never** pulled from or pushed back to the server, so they can't leak into the
web-served tree (nginx serves only `repo/`).

---

## Key rotation / repo recovery

A manual `workflow_dispatch` run (ref = a branch, not a tag) skips all the
tag-only build steps: it just re-signs the existing index with the configured
repo key and re-uploads. Use this to recover the repo or rotate infrastructure
**without** publishing a new APK.

---

## Push CI (non-tag)

Every push to any branch runs `.gitea/workflows/ci.yaml`: `lintDebug` →
`testDebugUnitTest` → `assembleDebug`, plus a Trivy filesystem scan on `main`.
Keep this green before tagging.

---

## F-Droid metadata

App store listing lives in `fdroid-metadata/` (`de.jeanlucmakiola.agendula.yml`
plus `en-US/` summary/description). Per-version changelogs are generated into the
repo's `metadata/.../en-US/changelogs/<versionCode>.txt` from `CHANGELOG.md` at
release time; metadata is uploaded alongside `repo/` so changelog history
survives across releases.

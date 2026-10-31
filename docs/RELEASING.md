# Releasing

Tagging publishes a build. On a commit whose `versionName` is `X.Y.Z`:

```sh
git tag vX.Y.Z
git push origin vX.Y.Z
```

`.github/workflows/release.yml` then checks that the tag and the committed
version agree, builds and signs both release flavours, and attaches them — with
`SHA256SUMS.txt` — to a GitHub release whose notes are that version's
`CHANGELOG.md` section. An existing tag can also be published by hand: Actions →
**Release** → *Run workflow*, with the tag as input.

Publishing needs four repository secrets (Settings → Secrets and variables →
Actions):

| Secret | What it is |
|---|---|
| `KEYSTORE_BASE64` | the release keystore, base64-encoded |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | key alias inside the keystore |
| `KEY_PASSWORD` | key password |

```sh
base64 -w0 release.jks > keystore.b64      # macOS: base64 -i release.jks -o keystore.b64
```

Without them the workflow fails instead of publishing an unsigned APK.

**Artifacts are one universal APK per flavour, with no ABI splits** (the app ships
no native code of its own, so a split would only multiply files):

- `Agendula-vX.Y.Z.apk` — `full`, CalDAV sync included.
- `Agendula-vX.Y.Z-offline.apk` — `offline`, no sync code and no network
  permission.

They install side by side (different application ids) and both install alongside
the upstream app.

## Version numbers

`versionName` and `versionCode` in `app/build.gradle.kts` are the source of
truth:

- `versionName` is `X.Y.Z`, or `X.Y.Z-beta.N` for a pre-release.
- `versionCode` is **derived** from `versionName` by
  `scripts/version_info.sh`, so it must match:

  | `versionName` | `versionCode` |
  |---|---|
  | `1.0.0` | `10000` |
  | `1.1.0-beta.1` | `1010001` |
  | `1.1.0` | `1010099` |

`bash scripts/version_info.sh --check` fails if the committed pair doesn't
agree; CI runs it on every pull request and the release workflow runs it against
the tag. Betas belong on `release/*` branches, never on `main`.

Keep `CHANGELOG.md` up to date under `## [Unreleased]`. The release notes for a
tag are that version's changelog section, rendered by
`scripts/release_notes.sh`; there is no store listing to fill in.

## Build

```sh
./gradlew :app:assembleDebug              # debug APKs, both flavours
./gradlew :app:assembleRelease            # signed release APKs, if key.properties exists
```

The build needs JDK 17 and the Android SDK. `:app` has two flavours, `full`
(CalDAV sync) and `offline` (no sync, no network permission); release artifacts
exist for both.

## Signing

`app/build.gradle.kts` reads a `key.properties` at the repository root — never
committed — with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
`storeFile` is resolved against the `app/` module, so a keystore at the repository
root is `storeFile=../release.jks`. Without the file the release variant falls
back to unsigned output, and the `releaseTest` variant (debug-signed, minified,
own application id) is what you install on a device to smoke-test a release
candidate.

Keep the key safe: every future update of the same application id must be signed
with it. If you lose it, users have to uninstall and reinstall.

## Continuous integration

`.github/workflows/ci.yml` runs on every pull request and is the required `ci`
check: the reproducibility invariant, version/`versionCode` agreement and
translation parity, then lint, unit tests, debug APKs and the R8 keep check.
Docs-only PRs skip the Android build but still report green.
`.github/workflows/translations.yml` is the separate, cheap `Translations / check`.

## Distribution

GitHub releases are the only channel, and they are automatic on a tag as
described at the top. Obtainium and similar tools can follow that feed. There is
no F-Droid or Google Play publishing in this repository: no store metadata, no
fastlane, no service account.

Nothing in this repository decides your application id — it is
`com.aiexile.agendula` in `app/build.gradle.kts`; changing it later means a new
app as far as Android is concerned, with no upgrade path from the old install.

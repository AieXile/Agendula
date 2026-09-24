# Official F-Droid submission

`de.jeanlucmakiola.agendula.yml` is the fdroiddata recipe for the official
F-Droid repo. Same model as Calendula: F-Droid rebuilds each tag from source,
checks it is byte-identical to our signed APK (`Binaries`), and publishes our
binary, so official and self-hosted installs share a signature and update each
other. A version that doesn't reproduce is skipped, never published wrong.

## Verified (2026-09-24)

- **From-source rebuild matches the distributed APK.** v0.4.0 rebuilt from its
  tag against the published `agendula_v0.4.0.apk`: 136 of 140 entries
  identical. The other four were datastore's `libdatastore_shared_counter.so`,
  stripped only because the local host had an NDK and CI doesn't. Fixed with
  `jniLibs { keepDebugSymbols += "**/*.so" }`; the rebuild now ships them
  byte-identical to the published APK. 1.0.0 is the first release with it.
- **Signing block is clean**: v2 signature + verity padding, no dependency
  metadata. `fdroid scanner` finds no non-free classes and no extra blocks.
- **All dependencies are FOSS** (no Play Services, Firebase or analytics).
  Crash reports are shown to the user and only sent by hand.
- **Committed version equals the tag-derived one**, so the pipeline's
  versionCode pin is a no-op and F-Droid building the tag as-is matches.
- **floret-kit** is pinned to a tagged commit on its Codeberg `main`, so the
  `submodules: true` checkout resolves from a clean clone.
- App signing cert SHA-256 (`AllowedAPKSigningKeys`) read from the published
  APK: `097946b3…af120`. Agendula's own key, not Calendula's.

`scripts/check_reproducible_release.sh` guards all of the above on every PR.

## Listing

Nothing listing-related goes in the recipe. F-Droid harvests
`fastlane/metadata/android/<locale>/` from the tagged source, the same tree Play
is fed from: title, descriptions, icon, feature graphic, screenshots and
`changelogs/<versionCode>.txt`. `title.txt` becomes the app name per locale.

## Submitting

After `v1.0.0` is tagged and `agendula_v1.0.0.apk` is live on the self-hosted
repo:

1. Fork `https://gitlab.com/fdroid/fdroiddata`, copy the recipe to
   `metadata/de.jeanlucmakiola.agendula.yml`.
2. `fdroid readmeta && fdroid lint de.jeanlucmakiola.agendula`, then
   `fdroid build -v -l de.jeanlucmakiola.agendula` to rebuild and verify.
3. Open the merge request. Later versions are picked up from new `vX.Y.Z` tags
   (`AutoUpdateMode`), so there is no per-release work in fdroiddata.

The recipe says `License: MIT`; `:dav` is vendored MPL-2.0 (`dav/PROVENANCE.md`).
If the reviewer asks, change it to `MIT AND MPL-2.0`.

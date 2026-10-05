# Release Process

Desktop and Android are released **independently**. Each platform has its own
version line, its own tag prefix and its own release workflow. Never tag both
platforms with the same ref.

Desktop additionally ships **two update channels** (Beta and Development);
see [Desktop update channels](#desktop-update-channels).

## Tag conventions

| Platform | Tag format | Example | Workflow | Produces |
| --- | --- | --- | --- | --- |
| Desktop | `v<version>-beta.N` | `v0.1.0-beta.15` | `release-beta.yml` | macOS/Windows installers + `latest.json` updater manifest |
| Android | `android-v<version>` | `android-v0.1.0-beta.1` | `release-android.yml` | Signed release APK on a GitHub prerelease |

Both platforms share the product line (`DropFlow v0.1.0`) but their beta
counters move independently. See the versioning policy in [CHANGELOG.md](../CHANGELOG.md).

## Desktop update channels

The desktop updater polls one of two channels. The user picks a channel in
Settings → About; the choice is persisted in app settings (`state.json`) and
survives updates. Existing installs default to **Beta**, and every channel
manifest carries the same release, so no migration is needed.

| Channel | Audience | Version line | Updater manifest |
| --- | --- | --- | --- |
| Beta | Curated, tested milestone releases | `0.1.0-beta.N` (tag `v0.1.0-beta.N`) | `releases/download/beta/latest.json` |
| Development | Frequent experimental builds | `0.1.0-dev.<run number>` (no tag) | `releases/download/development/latest.json` |

Not every experimental change should become a Beta release: Development
builds are published automatically from `main`, while a Beta release is cut
only when a Development milestone is coherent and tested. The maintainer
decides when to promote.

- Beta → Development: the newest Development build is always newer than any
  Beta, so switching offers the latest build immediately.
- Development → Beta: semver orders `0.1.0-beta.N` below `0.1.0-dev.N`, so a
  user on a recent Development build may not be offered the next Beta update.
  They should install the Beta build from the release page once, or keep the
  channel on Development until a newer Beta lands. See
  [Remaining concerns](#remaining-architectural-concerns).

There is no Stable channel yet. The channel enum in
`state_manager::UpdateChannel` and the settings schema leave room to add one
later without a data migration.

## Desktop Beta release (e.g. 0.1.0-beta.15)

1. Confirm `apps/desktop/src-tauri/Cargo.toml`, `tauri.conf.json` and
   `apps/desktop/package.json` all carry the target version.
2. Run the desktop validation gate: `cargo check && cargo test` (in
   `apps/desktop/src-tauri`) and `pnpm tsc --noEmit && pnpm build` (in
   `apps/desktop`).
3. Commit and tag: `git tag v0.1.0-beta.15 && git push origin v0.1.0-beta.15`.
4. `release-beta.yml` syncs the version from the tag, builds the macOS (aarch64)
   and Windows (x86_64) bundles via `tauri-action`, publishes the release with
   updater artifacts, and republishes `latest.json` to the floating `beta` tag
   the built-in updater polls.
5. `release-beta.yml` also generates release notes automatically from the
   conventional-commit history since the previous beta tag (`feat:` →
   Highlights, `perf:`/`refactor:` → Improvements, `fix:` → Bug Fixes; merge
   commits and unprefixed subjects are skipped). Refine the notes on the
   release page if desired; `CHANGELOG.md` remains the cumulative history.

## Desktop Development channel (automated)

`release-development.yml` runs on every push to `main` (and manually via
workflow_dispatch). **No git tags are created.**

- Builds the same macOS/Windows matrix and verifies each platform's updater
  manifest, using the same per-platform artifact protection as the Beta
  workflow.
- Versions the build `0.1.0-dev.<run number>`, synced into `package.json`,
  `tauri.conf.json` and `Cargo.toml` at build time, so the Beta counter stays
  reserved for milestone releases.
- Publishes installers and the merged `latest.json` to the floating
  `development` release and deletes installer assets from older runs so the
  release does not grow without bound.
- Builds may be unstable; the release body points testers at the Beta channel
  for curated milestones.

Promotion to Beta is a maintainer decision: tag a coherent milestone as
`v*-beta.*` and the Beta workflow takes it from there.

## Android Beta release (e.g. 0.1.0-beta.1)

1. Bump `versionName` / `versionCode` in `apps/android/app/build.gradle.kts`
   (`versionCode` only ever increases, so in-place installs upgrade).
2. Run `./gradlew assembleDebug` (and `assembleRelease` if signing is
   configured) in `apps/android`.
3. Commit and tag: `git tag android-v0.1.0-beta.1 && git push origin android-v0.1.0-beta.1`.
4. `release-android.yml` verifies the tag matches `versionName`, builds
   `assembleRelease`, renames the APK to `DropFlow-android-<version>.apk`, and
   attaches it to a GitHub prerelease titled `DropFlow Android <version>`.
   Android releases never touch the desktop updater manifest.

## Android signing

Release APKs must be signed or they cannot be installed. Two options:

- **CI (recommended)**: add these repository secrets once; every release is
  signed with the same key so users can upgrade in place:
  - `ANDROID_KEYSTORE_BASE64` — base64 of the `.jks` keystore
    (`base64 -i dropflow.jks | pbcopy`)
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`
- **Local**: create a gitignored `keystore.properties` at the repo root:

  ```properties
  storeFile=/absolute/path/to/dropflow.jks
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```

  `app/build.gradle.kts` picks it up automatically for `assembleRelease`.

If neither is configured, the CI workflow generates an **ephemeral** keystore so
the APK is still installable; the release notes then warn users to uninstall any
previous build first (the signing key differs every run).

## Pre-flight checklist (both platforms)

- [ ] Version numbers updated on the releasing platform only
- [ ] `CHANGELOG.md` has an entry for the release
- [ ] Commit subjects follow conventional-commit prefixes (feeds the
      auto-generated Beta release notes)
- [ ] Validation gates pass (see platform sections above)
- [ ] Protocol compatibility: DFP/1 unchanged, or cross-platform testing done
- [ ] Tag pushed with the correct prefix (`v*` desktop / `android-v*` Android)
- [ ] Release assets verified on the GitHub release page
- [ ] Both updater manifests verified (`beta` and `development` releases each
      serve a merged `latest.json` for darwin-aarch64 + windows-x86_64)

## Remaining architectural concerns

- **Development → Beta downgrade**: the updater only offers newer versions,
  and semver ranks `dev` above `beta` within the same `0.1.0` line, so a user
  switching from a recent Development build back to Beta may need to install
  that Beta build manually from the release page. A future improvement could
  special-case channel switches to ignore version comparison.
- **Dev channel storage growth**: only stale *installer* assets are pruned
  from the floating `development` release; `latest.json` and the current
  build's assets are kept. Monitor release size if build cadence increases.
- **Stable channel**: not implemented; the `UpdateChannel` enum and manifest
  URL scheme are ready for a third endpoint if it is ever needed.

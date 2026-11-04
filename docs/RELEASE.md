# Release Process

The current policy and migration rationale are in [VERSIONING.md](VERSIONING.md).
Desktop target: `0.1.1-beta.19`. Check it with
`node scripts/desktop-version.mjs check` before creating a release tag.

Desktop and Android are released **independently**. Each platform has its own
version line, its own tag prefix and its own release workflow. Never tag both
platforms with the same ref.

Desktop additionally ships **two update channels** (Beta and Development);
see [Desktop update channels](#desktop-update-channels).

## Tag conventions

| Platform | Tag format | Example | Workflow | Produces |
| --- | --- | --- | --- | --- |
| Desktop | `v<version>-beta.N` | `v0.1.1-beta.19` | `release-beta.yml` | macOS/Windows installers + `latest.json` updater manifest |
| Desktop Development | `v<version>-dev.N` | `v0.1.1-dev.10` | `release-development.yml` | macOS/Windows installers on the floating `development` release |
| Android | `android-v<version>` | `android-v0.1.0-beta.1` | `release-android.yml` | Signed release APK on a GitHub prerelease |

Desktop and Android core versions and prerelease maturity move independently.
See [VERSIONING.md](VERSIONING.md); protocol compatibility is tracked separately.

## Desktop update channels

The desktop updater polls one of two channels. The user picks a channel in
Settings → Updates; the choice is persisted in app settings (`state.json`) and
survives updates. Existing installs without a saved choice default to **Beta**.
Each channel has its own manifest and version line.

| Channel | Audience | Version line | Updater manifest |
| --- | --- | --- | --- |
| Beta | Curated milestone prereleases | `X.Y.Z-beta.N` (tag `vX.Y.Z-beta.N`) | `releases/download/beta/latest.json` |
| Development | Frequent experimental builds | `X.Y.Z-dev.N` (explicit tag or automatic run number) | `releases/download/development/latest.json` |

Not every experimental change should become a Beta release: Development
builds are published automatically from `main`, while a Beta release is cut
only when a Development milestone is coherent and tested. The maintainer
decides when to promote.

- Switching between Beta and Development offers the target channel's release
  even when plain semver would order it below the installed release. Within
  one line, only strictly newer builds are offered. This is implemented and
  tested in `update_manager.rs`; channel changes invalidate pending checks and
  downloads rather than reusing an update from the old channel.

The About panel reports the **installed build's** version and release line.
That line is independent of the selected update channel. CI supplies
`DROPFLOW_RELEASE_TAG`; local builds without it derive their line from the
package version, so a local `0.1.0-beta.17` build is labeled `beta`, not `stable`.

There is no Stable channel yet. The channel enum in
`state_manager::UpdateChannel` and the settings schema leave room to add one
later without a data migration.

## Desktop Beta release

1. Set the target with `node scripts/desktop-version.mjs set <version>`.
   Run `node scripts/desktop-version.mjs check` to verify package.json,
   tauri.conf.json, Cargo.toml and Cargo.lock agree.
2. Run the desktop validation gate: `cargo check && cargo test` (in
   `apps/desktop/src-tauri`) and `pnpm tsc --noEmit && pnpm build` (in
   `apps/desktop`).
3. Commit, then tag that exact version, for example `v0.1.1-beta.19`.
   Never move/reuse an already published version tag.
4. `release-beta.yml` verifies that the tag matches all manifests, builds the macOS (aarch64)
   and Windows (x86_64) bundles via `tauri-action`, publishes the release with
   updater artifacts, and republishes `latest.json` to the floating `beta` tag
   the built-in updater polls.
5. `release-beta.yml` also generates release notes automatically from the
   conventional-commit history since the previous beta tag (`feat:` →
   Highlights, `perf:`/`refactor:` → Improvements, `fix:` → Bug Fixes; merge
   commits and unprefixed subjects are skipped). Refine the notes on the
   release page if desired; `CHANGELOG.md` remains the cumulative history.

## Desktop Development channel (automated)

`release-development.yml` runs on pushes to `main`, explicit `v*-dev.*` tags,
and manually via workflow_dispatch. Automatic runs now create a versioned
Development release/tag at the built commit; explicit tags retain their number.
To publish an exact build number, commit the validated desktop change, create
`v0.1.1-dev.10` at that commit and push that tag. Avoid pushing the same commit to
`main` at the same time: that would start a second automatically numbered build.

- Builds the same macOS/Windows matrix and verifies each platform's updater
  manifest, using the same per-platform artifact protection as the Beta
  workflow.
- Uses the exact Development tag or derives `<target-core>-dev.<run number>` for
  branch/manual runs, synced into `package.json`,
  `tauri.conf.json`, `Cargo.toml` and `Cargo.lock` at build time, so the Beta counter stays
  reserved for milestone releases.
- Runs version-resolver tests, frontend regression checks/build and locked Rust
  tests before publishing; one canonical version feeds both platform manifests.
- Stages installers in a draft versioned Development release. Only
  after both platforms succeed does it publish the merged `latest.json` to the
  versioned release, publishes it, and updates the floating `development` channel. Failed/partial runs
  leave the last complete channel intact. Legacy floating assets are retained.
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

- **Dev channel storage growth**: only stale *installer* assets are pruned
  from the floating `development` release; `latest.json` and the current
  build's assets are kept. Monitor release size if build cadence increases.
- **Stable channel**: not implemented; the `UpdateChannel` enum and manifest
  URL scheme are ready for a third endpoint if it is ever needed.

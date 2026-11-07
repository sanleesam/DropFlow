# DropFlow versioning policy

DropFlow follows [Semantic Versioning 2.0.0](https://semver.org/). Existing
published tags and artifacts are immutable; old beta numbering is not rewritten.

## Compatibility contract

Versioning describes the app's file-transfer behavior, DFP wire compatibility,
discovery metadata and persisted user-state compatibility. The wire protocol
has its own `DFP/1` identifier; app versions are not protocol versions.

- `MAJOR.MINOR.PATCH`: compatible fixes increment PATCH; new compatible
  functionality increments MINOR. While MAJOR is zero, incompatible product
  changes increment MINOR and must be documented with migration requirements.
- `-alpha.N`: incomplete functionality and early usability testing.
- `-beta.N`: implemented features under wider compatibility testing.
- `-rc.N`: release candidate after the functional release matrix passes.
- No suffix: a normal release, not automatically a promise of 1.0 API stability.
- `-dev.N`: experimental CI builds. N is the workflow run number; the core
  MAJOR.MINOR.PATCH is derived from the current desktop target, never hard-coded.
- Release metadata (`+...`) is not used for shipped versions: SemVer ignores it
  for upgrade ordering. Git commit identity remains in GitHub release metadata.

## Desktop source of truth and upgrade migration

`apps/desktop/package.json` is the canonical desktop target. Run:

```sh
node scripts/desktop-version.mjs set 0.1.1-beta.19
node scripts/desktop-version.mjs check
node --test scripts/desktop-version.test.mjs
```

The setter synchronizes package.json, tauri.conf.json, Cargo.toml and Cargo.lock.
The checker fails builds if they drift. Tagged Beta builds must match the
committed target; CI no longer silently changes the target to match a wrong tag.
Development CI derives, for example, `0.1.1-dev.8` from that same core version.

The published desktop fix release is `0.1.1-beta.19`. Keeping the counter above 17 is intentional:
legacy Beta 17 updaters compare only beta counters. Starting at beta.1 would
strand those installs despite the newer core version. The new updater compares
full SemVer within each channel, including future core-version transitions.
Beta and Development channel switching remains an explicit product preference
and can offer the other channel's build even when it sorts lower in SemVer.
Normal releases never auto-downgrade to prereleases.

The `v0.1.1-beta.18` build was cancelled before publication when a release-history
audit found that published Development 8 fixes had not reached main. Its tag is
retained unchanged; beta.19 includes those fixes instead of reusing that tag.

The current supported publication channels remain Beta and Development. Alpha,
RC and normal versions are valid policy stages, not a claim that their desktop
publication workflows or separate updater endpoints have been implemented.

## Android

Android is versioned independently; its early-alpha status is represented by
published `0.2.0-alpha.2`, not by renaming published `0.1.0-beta.1`.
An incremented Android `versionCode` is mandatory for every shipped APK, and the
original signing key is required for in-place upgrades. UI parity with desktop
does not imply equivalent maturity, release channel or protocol support.
Android's current unreleased source target is `0.2.0-alpha.3` (versionCode 20),
above the verified public Alpha 2 baseline (versionCode 19).
`node scripts/android-version.mjs check` verifies its source/tag/upgrade ordering
against the inspected published baseline. Legacy Beta 1 used an ephemeral key;
the maintainer approved a permanent-key migration with a one-time reinstall for
Alpha 1. The old fingerprint remains in `signing-policy.json`'s migration record.
Release CI enforces the permanent certificate for subsequent releases;
Alpha/Beta/Stable Android manifests remain separate from desktop.
See `ANDROID_ALPHA2.md` and `ANDROID_UPDATES.md` for implementation and open gates.

## Release gates

Record exact tests and their results. Builds and loopback peers do not prove
physical Mac/Windows/Android LAN discovery or transfer interoperability.
Publish with the appropriate prerelease label when hardware/platform coverage
is incomplete, and list the remaining limitations instead of marking them passed.

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
node scripts/desktop-version.mjs set 0.1.1-beta.18
node scripts/desktop-version.mjs check
node --test scripts/desktop-version.test.mjs
```

The setter synchronizes package.json, tauri.conf.json, Cargo.toml and Cargo.lock.
The checker fails builds if they drift. Tagged Beta builds must match the
committed target; CI no longer silently changes the target to match a wrong tag.
Development CI derives, for example, `0.1.1-dev.8` from that same core version.

The next desktop fix release is `0.1.1-beta.18`. Counter 18 is intentional:
legacy Beta 17 updaters compare only beta counters. Starting at beta.1 would
strand those installs despite the newer core version. The new updater compares
full SemVer within each channel, including future core-version transitions.
Beta and Development channel switching remains an explicit product preference
and can offer the other channel's build even when it sorts lower in SemVer.
Normal releases never auto-downgrade to prereleases.

The current supported publication channels remain Beta and Development. Alpha,
RC and normal versions are valid policy stages, not a claim that their desktop
publication workflows or separate updater endpoints have been implemented.

## Android

Android is versioned independently; its early-alpha status should be represented
by the next unreleased `0.2.0-alpha.1`, not by renaming published `0.1.0-beta.1`.
An incremented Android `versionCode` is mandatory for every shipped APK, and the
original signing key is required for in-place upgrades. UI parity with desktop
does not imply equivalent maturity, release channel or protocol support.
Android version changes and release gates are a separate phase after desktop.

## Release gates

Record exact tests and their results. Builds and loopback peers do not prove
physical Mac/Windows/Android LAN discovery or transfer interoperability.
Publish with the appropriate prerelease label when hardware/platform coverage
is incomplete, and list the remaining limitations instead of marking them passed.

# Android 0.2.0-alpha.1 preparation — 2026-11-04

Source target: `0.2.0-alpha.1`, versionCode 18. **Not published.** The actual
published Beta 1 APK was inspected: versionCode 17, package `com.dropflow.android`,
certificate SHA-256 `f364ad7616f35c03d3b7982b912c95964054252a5dee833986a8ccb362dbd8cd`.
Those public facts are recorded in `apps/android/release-baseline.json`.

## Implemented

- Preserved and extended the existing native Compose Titanium/Icy Blue redesign.
  Home, Devices, Transfers and Settings share navigation, surfaces, controls,
  typography, device cards, pulse discovery and transfer presentation. About and
  Updates are Settings destinations. Larger windows use a navigation rail.
- Home has nearby-device selection, the same Send files hierarchy and recent
  activity as desktop. Devices has real search/counts and an adaptive grid.
  Android uses its native SAF picker, not an imitation desktop file browser.
- The actual version determines Alpha/Beta/normal labels; the toolbar no longer
  hard-codes BETA 2. Existing history/transfer ViewModels, NSD and DFP/1 are retained.
- Outgoing authorization waits up to 75 seconds, then restores the 15-second
  streaming timeout. The existing receiver decision period remains 60 seconds.
- Android update checks, Alpha/Beta/Stable channel preference, progress/cancel,
  bounded private-cache downloads, SHA-256/size checking, APK identity/signing
  preflight and a user-confirmed Android installer handoff. Checks are manual;
  there are no silent background checks or automatic installs.
- Android's release workflow checks SemVer/install-number ordering, requires the
  original signing certificate, generates versioned APK/JSON assets and publishes
  a separate Android channel only after successful validation. Older runs cannot
  move a channel backwards. See [ANDROID_UPDATES.md](ANDROID_UPDATES.md).

Android's static layered surfaces deliberately do not claim macOS behind-window
vibrancy or GSAP support. Native Compose provides the matching material/motion
language and respects the saved reduced-motion preference.

## Current verification

- 21 Android JVM tests passed, including approval/streaming timeout phases,
  version labels, receiver integrity and updater ordering/URL/hash/length policy.
- Type-specific version and manifest tests plus desktop version/workflow tests:
  16 Node tests passed. Workflow YAML parses; versionName/tag/code checks pass.
- Android lint, debug APK, unsigned release APK and test APK builds passed.
  An unsigned local release APK is not an installable signed release.
- Real emulator receiver regression checks passed: incoming prompt on Settings,
  two files including an empty file, decline on Transfers, malformed manifest,
  substituted header, checksum cleanup, sender cancel, concurrent requests and
  progress/local cancellation across About and Transfers.
- Native emulator navigation passed for Home, Devices, Transfers, Settings,
  About and Updates. The real Alpha endpoint correctly reports no published
  channel release yet. About shows `0.2.0-alpha.1` and `Alpha · Debug build`.
- Beta channel selection survived an app force-stop/relaunch and checked the
  Beta endpoint rather than Alpha. The original Alpha preference was restored.
- A later restarted emulator reported a GitHub DNS failure. The updater exposed
  the error instead of claiming it was current; connectivity errors have readable
  recovery messages. This later run does not replace the earlier online result.
  Final navigation checks passed in that state, including the About/Updates
  back buttons returning to Settings and the correct Alpha debug identity.
- A separate test APK exercised the real Android JSON parser and APK parser:
  installed debug APK identity/signing preflight passed; mismatched metadata,
  fractional install numbers and the unsigned release APK were rejected.
  It never launched or approved an installer.
- Real Android SAF selection sent three files to a loopback DFP/1 peer after
  a 20-second recipient delay: Unicode text, an empty file and a 1 MiB binary.
  Exact bytes and SHA-256 matched. A test-only peer fixture supplied the address;
  this is not proof of physical LAN discovery or a new Mac↔Android app-to-app test.

Reproduce builds from the repository root:

```sh
node --test scripts/*version.test.mjs scripts/android-updater-manifest.test.mjs scripts/release-workflow.test.mjs
node scripts/android-version.mjs check android-v0.2.0-alpha.1
ANDROID_HOME="$HOME/Library/Android/sdk" apps/android/gradlew -p apps/android testDebugUnitTest lintDebug assembleDebug assembleRelease
ANDROID_HOME="$HOME/Library/Android/sdk" python3 scripts/android-ui-smoke.py
ANDROID_HOME="$HOME/Library/Android/sdk" python3 scripts/verify-android-receiver.py
```

The UI/receiver scripts are restricted to an emulator. Test-only instrumentation
is not packaged into release APKs. Current screenshots are under
`/private/tmp/dropflow-alpha1-qa`; these are disposable local evidence, not repo assets.

## Required before Android publication

1. Recover/configure the original signing key and verify an actual Beta 1 →
   Alpha 1 in-place upgrade. The public certificate fingerprint is not a private
   signing key. CI refuses a different signer; it never generates a replacement.
2. Verify physical Android↔Mac/Windows discovery and transfer behavior. Emulator
   address fixtures do not prove ordinary LAN discovery. No physical device was
   attached during this run.
3. Verify a real published, same-key update end to end, including interrupted
   download recovery, channel changes and the OS installation approval flow.
   Alpha/Beta/Stable manifests are created on the first successful Android release
   for each channel; no Android release/channel was published by this work.
4. Android 8/9 storage/installer acceptance and larger-screen/font/motion coverage
   still need device-level acceptance. Background receiving, notifications,
   persisted history/trust and seekable SAF resume remain unimplemented.

The older [Beta 2 report](ANDROID_BETA2.md) and
[Mac/emulator observations](ANDROID_MAC_EMULATOR_TEST.md) are dated history.
They must not be treated as proof for this candidate or the new updater.

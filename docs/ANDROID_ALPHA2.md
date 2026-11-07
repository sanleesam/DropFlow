# Android Alpha 2 validation

Candidate: `0.2.0-alpha.2` (install number 19). Alpha 1 used install number 18
with the same pinned certificate. This is an experimental Android-only release;
no desktop version, release workflow, protocol framing, or installed app is changed.

## Before tagging

- Android: 24 JVM tests, lint, debug and signed release assembly. New regression
  tests cover path traversal, single-transfer ownership, and competing legacy
  file publication without overwrites. Eleven Node release-tooling tests pass.
- Emulator receiver: approval from Settings, two-file and empty-file receives,
  nested Unicode paths and collision copies, decline, invalid request/header,
  traversal, bad checksum cleanup, both cancellation paths, repeated busy
  requests, and history after restart. Clearing history retained received bytes.
- Emulator sender: real SAF multi-selection, a 17-second approval wait,
  a 1 MiB file and an empty file with exact hashes, and Finishing until final ACK.
  Test-only endpoints supply addresses; this does not prove LAN discovery.
- Android package-parser preflight: private `.part` APK parsing and matching
  signer worked; mismatched metadata and fractional install numbers were rejected.
- Android signing: the local release APK uses the same pinned SHA-256 certificate
  as the downloaded public Alpha 1 APK. Install number and package identity
  were inspected separately. Installing Alpha 2 over public Alpha 1 preserved
  the test device name without reinstalling.
- The initial signed smoke caught a stale version string after an incremental
  build: package metadata was Alpha 2, but About displayed Alpha 1. Runtime
  identity now comes from Android's package manager for About, discovery, and
  updater eligibility rather than inlined version constants. The corrected
  signed build passed Home, Devices, Transfers, Settings, About and Updates
  navigation, displayed Alpha 2 in both version views, and read the live Alpha feed.

The tests use a disposable, read-only emulator overlay; no phone, host Wi-Fi,
firewall, or desktop configuration was modified.

## Reproduce

```sh
node scripts/android-version.mjs check android-v0.2.0-alpha.2
node --test scripts/android-version.test.mjs scripts/android-updater-manifest.test.mjs scripts/release-workflow.test.mjs
apps/android/gradlew -p apps/android testDebugUnitTest lintDebug assembleDebug assembleRelease
python3 scripts/verify-android-receiver.py
python3 scripts/verify-android-sender.py
```

Install the debug APK on the emulator before running either script. The sender
script also requires the separate test APK, built with
`-PdropflowInteropRunner=com.dropflow.android.InteropInstrumentation`.
Production APKs contain no fixture. Use the release tag to reproduce its exact
source version after main advances.

## Remaining limits

- Physical Android ↔ macOS/Windows automatic discovery and large-file transfers
  are not certified by emulator fixtures. Android 8/9 legacy storage has JVM
  collision coverage, not a device-level storage or installer pass.
- Background receiving/notifications, authenticated pairing and encrypted
  transfers, resumable SAF streams, and full OS/vendor coverage remain separate
  work. One incoming or outgoing transfer runs at a time.
- Local cancellation closes the socket instead of interleaving a cancellation
  frame. Older peers may show a lost connection; Android shows Cancelled for
  the local operation. A sender that disconnects while approval is pending can
  leave the prompt visible until the bounded approval timeout or a decision.
- The older Android 8/9 publication path creates collision-safe copies from
  verified staging data. It does not claim crash-atomic publication on legacy
  external storage. MediaStore pending rows provide the modern storage path.

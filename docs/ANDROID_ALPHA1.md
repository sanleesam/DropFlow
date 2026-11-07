# Android 0.2.0-alpha.1 preparation — updated 2026-11-05

This is a historical record. See [Alpha 2 validation](ANDROID_ALPHA2.md)
for the current release and its test results.

Released: `0.2.0-alpha.1`, versionCode 18, on 2026-11-05. The actual
published Beta 1 APK was inspected: versionCode 17, package `com.dropflow.android`,
certificate SHA-256 `f364ad7616f35c03d3b7982b912c95964054252a5dee833986a8ccb362dbd8cd`.
The legacy certificate is preserved in `apps/android/signing-policy.json`'s
migration record. `release-baseline.json` now records the verified public Alpha
1 artifact. The next unreleased source target is Alpha 2, versionCode 19.

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
  permanent signing certificate, generates versioned APK/JSON assets and publishes
  a separate Android channel only after successful validation. Older runs cannot
  move a channel backwards. See [ANDROID_UPDATES.md](ANDROID_UPDATES.md).

Android's static layered surfaces deliberately do not claim macOS behind-window
vibrancy or GSAP support. Native Compose provides the matching material/motion
language and respects the saved reduced-motion preference.

## Current verification

- 21 Android JVM tests passed, including approval/streaming timeout phases,
  version labels, receiver integrity and updater ordering/URL/hash/length policy.
- Type-specific version and manifest tests plus desktop version/workflow tests:
  18 Node tests passed, including the explicit signing-migration and immutable
  retry guards. Workflow
  YAML parses; versionName/tag/code checks pass.
- Android lint, debug APK, unsigned release APK and test APK builds passed.
  An unsigned local release APK is not an installable signed release.
- After permanent-key configuration on 2026-11-05, the signed release build,
  unit tests and lint passed. `apksigner` verified the APK signature; its signer
  matches `signing-policy.json`, and embedded identity is `com.dropflow.android`,
  version `0.2.0-alpha.1`, install number 18. The Apache license and scope/notice
  files are bundled inside the APK. This is artifact validation, not a claim of
  a completed physical-phone installation.
- The signed release APK also installed and passed emulator navigation and
  updater-unpublished-state checks, including the plain `Alpha` release label
  (not `Debug build`). No OS installer permission was enabled automatically.
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

Reproduce Alpha 1 builds from a checkout of the immutable
`android-v0.2.0-alpha.1` tag (current main intentionally targets the next Alpha):

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

## Signing migration and experimental release decision

The original successful Beta 1 workflow logs explicitly report an ephemeral
keystore, and the public release notes warn that its APK requires reinstalling.
On 2026-11-05 the maintainer approved a fresh permanent identity and a one-time
reinstall migration. A new 4096-bit RSA/PKCS12 key was created outside the
repository, with private local backups and CI secret configuration. The public
fingerprint is pinned in `apps/android/signing-policy.json`. The historical Beta
1 baseline is preserved unchanged. No user's installed app was uninstalled.

An explicitly experimental Alpha may be published after automated checks and
signed-artifact verification; remaining device-level checks below must be
disclosed, not represented as passed. This replaces the earlier impossible
original-key recovery requirement; it is not an assertion of production readiness.

The first CI attempt stopped before publication at the certificate guard. The
cause was independently reproduced: build-tools 37 prints `V2 Signer:` whereas
the old parser expected `Signer #1`. Both tools report the same permanent
certificate. Release verification now pins build-tools 34 (matching the project's
tested toolchain), prints the public certificate report and supports manually
retrying the existing unpublished tag without moving it. This does not weaken
the expected-certificate comparison or create a different signing key.

## Published artifact verification

- [Recovery workflow run 42008230594](https://github.com/sanleesam/DropFlow/actions/runs/42008230594)
  passed all gates and published [Android Alpha 1](https://github.com/sanleesam/DropFlow/releases/tag/android-v0.2.0-alpha.1).
  It built the original tag at `c7c9062ca8c8ade7795a3c1d0b9807819c920d23`;
  the tag was not moved. Main's workflow-only tooling correction drove the retry.
- Downloaded public APK: 11,523,434 bytes; SHA-256
  `8cf23177d3e4a0dae681bfd0c697d8deaa42772bbd1d1c816eeb6c3dc2f2f5c6`.
- `apksigner` verified its signature and the pinned permanent certificate.
  Package/version/code match `com.dropflow.android`, `0.2.0-alpha.1`, 18.
- The public versioned manifest and floating `android-alpha/latest.json` match
  exactly, including the immutable APK URL, size and hash. Embedded LICENSE,
  NOTICE and THIRD_PARTY_NOTICES.md match the source files byte-for-byte.
- The downloaded CI APK installed over the same-version, same-key local release
  in the emulator. The live Alpha check reported `No newer Alpha update`; Home,
  Devices, Transfers, Settings, About and back navigation passed with the release
  identity. This is not a newer-version upgrade or physical-device installation.

Recheck the public Alpha 1 APK UI from current main with:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" python3 scripts/android-ui-smoke.py --release --expect-published --expected-version 0.2.0-alpha.1
```

## Remaining device-level acceptance

1. Verify physical Android↔Mac/Windows discovery and transfer behavior. Emulator
   address fixtures do not prove ordinary LAN discovery. No physical device was
   attached during this run.
2. Verify a real published, same-key update end to end, including interrupted
   download recovery, channel changes and the OS installation approval flow.
   Alpha/Beta/Stable manifests are created on the first successful Android release
   for each channel; Alpha is now published. Beta/Stable are not populated by
   this Alpha publication.
3. Android 8/9 storage/installer acceptance and larger-screen/font/motion coverage
   still need device-level acceptance. Background receiving, notifications,
   persisted history/trust and seekable SAF resume remain unimplemented.

The older [Beta 2 report](ANDROID_BETA2.md) and
[Mac/emulator observations](ANDROID_MAC_EMULATOR_TEST.md) are dated history.
They must not be treated as proof for this candidate or the new updater.

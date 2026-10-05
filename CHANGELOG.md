# Changelog

All notable changes to DropFlow are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/).

## Versioning policy

Desktop and Android are released **independently** and use **independent beta
numbering**. Both platforms are part of the DropFlow v0.1.0 product line, but
their beta counters do not move in lockstep:

- **Desktop** (Tauri): `0.1.0-beta.N` — continues its existing beta line
  (released as GitHub tags, e.g. `v0.1.0-beta.15`, and distributed through the
  built-in updater).
- **Android** (APK): `0.1.0-beta.1`, `0.1.0-beta.2`, … — its own public beta
  cycle, starting fresh at `beta.1`. The Android `versionCode` increases
  monotonically across all releases so in-place installs always upgrade.

Do not assume the two platforms share a version number.

## Desktop — [0.1.0-beta.15]

First desktop milestone intended for real users on Windows and macOS.

### Added

- Structured file+stderr logging on desktop (`<app data>/logs/dropflow.log`,
  5 MB rotation, `RUST_LOG` override) covering every discovery and transfer stage.
- mDNS daemon event monitoring: interface changes, name conflicts and socket
  errors are now logged instead of failing silently.
- Discovery idle watchdog: if no mDNS responses arrive, the log and the desktop
  empty state point at the most common cause (OS firewall blocking UDP 5353).
- Desktop advertisement now carries `platform`, `operating_system` and
  `application_version` TXT records, matching the Android advertisement.
- macOS-specific logging hints for firewall/VPN multicast issues.

### Fixed

- Clearing transfer history in Settings no longer leaves ghost entries on the
  dashboard or resurrect them on the next transfer; the dashboard now follows
  the backend `history-updated` event as the source of truth.
- macOS discovery: instrumented the complete pipeline (advertisement, browsing,
  resolution, TXT validation, interface selection). The remaining cause of
  "macOS discovers nothing" on real hardware is inbound mDNS (UDP 5353) being
  blocked for the unsigned binary — see the troubleshooting section of the
  release notes. All software-side gaps are fixed and observable in logs.
- Desktop receiver finalized renamed files before the session was fully
  verified; a failed completion could leave partial files behind. Files are now
  promoted only after every file, checksum and byte count has verified.
- Removed the unused `incoming-transfer-dismiss` desktop event and unused
  field from the desktop `TransferProgressPayload`.

### Changed

- Dashboard discovery empty state is calm and jargon-free: "Looking for
  devices…" first, with a friendly troubleshooting hint only after ~15 seconds
  (no ports, protocols or firewall talk in the interface).
- Settings polish: honest Appearance controls (dark theme shown as the current
  theme, "Reduce animations" now actually takes effect), redundant per-tab
  headings removed, dialogs annotated for screen readers, Escape closes the
  clear-history confirmation without dismissing the whole Settings modal.
- Byte counts and speeds are formatted consistently (shared `formatters` util)
  — small files no longer display as "0.0 MB".
- Duplicated byte/speed formatters consolidated; the unused `greet` Tauri
  command and scratch `tauri-icon-test/` assets were removed.
- Release engineering: desktop and Android release pipelines are independent;
  desktop tags (`v*`) can no longer be confused with Android tags
  (`android-v*`), which drive the new `release-android.yml` workflow.

### Compatibility

- DFP/1 protocol unchanged; desktop remains the reference implementation.

## Android — [0.1.0-beta.1]

First public Android beta cycle (versionCode 17). Desktop and Android are
released independently — see the versioning policy above.

### Added

- Discovery, send (Android → Desktop) and receive (Desktop → Android) with the
  shared DFP/1 protocol, plus Home, History, Settings and About screens.
- Structured NSD logging for every discovery stage.
- Android release pipeline: `.github/workflows/release-android.yml` builds and
  signs the release APK and attaches it to a GitHub prerelease when an
  `android-v*` tag is pushed. Signing credentials are injected via repository
  secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
  `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`); local builds can be signed by
  dropping a gitignored `keystore.properties` at the repo root. The workflow
  verifies the tag matches `versionName` before building.

### Fixed

- Receiver publishes output files only after the whole session verifies
  (matching desktop semantics); failed sessions leave no partial files.
- "Rescan" no longer fails with `FAILURE_ALREADY_ACTIVE`; NSD stop is
  asynchronous and the restart now waits and auto-retries.
- Device rename in Settings re-registers the mDNS advertisement.
- Incoming-transfer sheet no longer leaks the "trust device" checkbox state
  from a declined request into the next request.
- ViewModels are provided via `ViewModelProvider`, so rotating the device no
  longer re-creates discovery (previously producing duplicate mDNS
  registrations) and history survives rotation.
- Send failures show a real reason (declined, cancelled by the recipient,
  timeout, connection lost) instead of a generic message.
- `TRANSFER_CANCEL` received mid-stream is handled explicitly and cleans up
  partial files.

### Changed

- Settings and About screens use plain, user-facing wording: protocol
  internals (ports, mDNS service type, multicast group) are gone, and the
  About screen now describes the app instead of listing build specs.
- Home screen copy is user-facing ("Files are sent straight to the selected
  device — nothing leaves your network") instead of exposing TCP port and
  SHA-256 implementation details.
- Device status card reflects the real transfer-server state (Discoverable /
  Starting… / Offline / Transfer error).
- Discovery errors are surfaced in the empty state; duplicated byte/speed
  formatters consolidated into `core/util`; unused `DropFlowHeader` composable
  and dead transfer paths were removed.

### Compatibility

- DFP/1 protocol unchanged; interoperable with desktop 0.1.0-beta.15.

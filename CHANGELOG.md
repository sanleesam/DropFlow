# Changelog

All notable changes to DropFlow are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/).

## Versioning policy

Desktop and Android use independent Semantic Versions and maturity stages.
See [the current policy](docs/VERSIONING.md). Published versions are not renamed;
Android's numeric `versionCode` only increases, including when moving from the
legacy beta label to the next alpha product version.

## Desktop — [0.1.1-beta.19] — 2026-11-04

- Fix folder sending: recursively expand files and preserve nested relative
  paths. Empty directories and symbolic links are not transferred.
- Respect the configured receive directory and validate received headers,
  session completion IDs, totals, path boundaries and final acknowledgements.
- Save verified files before acknowledging completion; surface finalization
  errors instead of reporting success when file promotion fails.
- Allow the recipient's full approval period before applying streaming timeout.
- Fix full SemVer comparison within updater channels, including patch/minor
  transitions and normal-release upgrades; synchronize all desktop version
  manifests and derive Development versions from the target core version.
- Prevent file-browser rename from silently overwriting an existing file.
- Preserve failed selections for manual retry and format IPv6 socket addresses
  correctly. Show unavailable storage information instead of perpetual loading.
- See `docs/VERSIONING.md` for migration and Android alpha versioning policy.
- Integrate the already-published Development 8 discovery recovery fixes into
  main so the Beta release does not regress them. The cancelled beta.18 tag is
  retained unchanged and has no completed release.

## Desktop — Development (after Beta 17)

- Replaced the desktop Dock/app icon with the new silver/icy-blue file-transfer
  mark, including transparent macOS ICNS, Windows ICO and desktop PNG sizes.
- Reused the transparent mark in the sidebar, About panel and browser favicon.
- Beta 17 and mobile icons are unchanged.

## Desktop — [0.1.0-dev.8] — 2026-10-27

- Repair Search again/Refresh so it rebuilds discovery sockets, refreshes the
  advertisement and removes stale peers before resolving the network again.
- Add one bounded startup recovery attempt and recover an empty browser when
  returning to the app; upgrade mDNS discovery to 0.21.6 for published recovery
  fixes around multicast joins, sleep, unresolved services and event pressure.
- Remove competing rename advertisements that could publish an invalid device
  type or reveal a hidden device. Preserve current visibility during recovery.
- Honor requested diagnostic log levels in release builds and include the macOS
  local-network purpose description in the application bundle.
- Support explicit Development tags and run regression checks before publishing.
- See [the discovery audit](docs/DEV8_DISCOVERY_AUDIT.md) for live Mac/Windows
  evidence, the unproven original trigger, and remaining transport/settings issues.

## Desktop — [0.1.0-beta.17] — 2026-10-16

### Changed

- Complete desktop UI refresh across Home, Devices, Transfers, Files and
  Settings with shared Titanium + Icy Blue surfaces and reusable controls.
- Native full-window backdrop materials with opaque accessibility/platform
  fallbacks, reflective glass edges and clearer focus states.
- GSAP discovery pulses and restrained page transitions that respect the
  application and system reduced-motion preferences.
- Improved transfer status, file-selection feedback, notifications and
  Beta/Development update controls using the existing application providers.
- Removed the repository's `AGENTS.md` instructions at the maintainer's request.

### Fixed

- Local prerelease builds no longer report their installed release line as
  "stable" when a CI release tag is absent. The selected update channel remains
  an independent preference.

### Validation notes

- Earlier native Files-page validation was blocked during filesystem access;
  the maintainer subsequently confirmed normal operation with folder permission
  granted. The Beta 17 local native check also successfully listed the Desktop
  folder. No filesystem or permission behavior was changed for this release.
- Windows native visuals, updater installation and physical cross-device
  transfers have not been validated as part of this UI refresh.
- Transfer protocol and discovery behavior are unchanged by this redesign.

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

## Android — [0.2.0-alpha.2] — Unreleased

- Reserved next source target after publishing Alpha 1; no second Alpha release
  is published yet. Install number 19 is above the verified Alpha 1 baseline.

## Android — [0.2.0-alpha.1] — 2026-11-05

The earlier, never-published `0.1.0-beta.2` candidate is superseded by the Alpha
line. Published Beta 1 is not renamed. VersionCode 18 remains above its public 17.

- Unified Home/Devices/Transfers/Settings navigation, larger-window rail,
  adaptive device search/grid and desktop-style Send files/recent activity.
- Correct release-derived Alpha labels; About and Updates live in Settings.
- Separate 75-second outgoing approval wait and 15-second streaming timeout.
- Manual Android channel updates with bounded private-cache downloads, progress,
  cancellation, size/SHA-256 checking, APK identity/signing preflight and an
  Android-controlled installation confirmation flow.
- Independent Alpha/Beta/Stable update manifests, version/install ordering and
  permanent-certificate gates in Android release CI. Alpha APK and channel published.
- Permanent signing identity with an explicitly documented, one-time reinstall
  from legacy Beta 1; future releases retain the new key.
- Apache-2.0 for original project code, third-party scope/attribution notices,
  and license files embedded in the Android APK.
- Current evidence and limits: [ANDROID_ALPHA1.md](docs/ANDROID_ALPHA1.md).

### Preserved from the prior candidate

### Changed

- Complete native Compose redesign of Home, History, Settings, About, incoming
  prompts and live progress using desktop Titanium + Icy Blue design tokens,
  reflective panel rims, layered graphite surfaces and restrained motion.
- Shared silver/blue desktop mark in Android branding and adaptive launcher icon.
- Global incoming prompts, live transfer progress and result messages on every
  page; scrollable screens and bounded long names keep controls reachable.
- Explicit device-name save, meaningful download behavior descriptions, and a
  persisted Reduce animations setting. Removed ineffective overwrite, auto-open
  and alternate-accent controls from the UI; existing stored preferences remain.
- History and trust are clearly described as applying to the current app session.
- Higher contrast primary buttons, labeled switches and dark result messages;
  old result messages do not replay after screen rotation.
- The prior target was `0.1.0-beta.2`; this released Alpha is `0.2.0-alpha.1`, versionCode `18`.

### Fixed

- Receiver validates file counts, totals, file headers and SHA-256 completion
  checksums against the accepted manifest before publishing output.
- Final success acknowledgment now follows successful storage publication;
  failed publication cleans up already published targets and temporary rows.
- A concurrent incoming request no longer replaces an unanswered prompt.
- Incoming connection lifecycle updates reach the app's session state.
- Device rescan clears stale selection; asynchronous device and history events
  update state atomically. Outgoing start uses an atomic duplicate-send guard.
- Android storage API-level annotation now permits the full lint gate to pass.
- Release CI requires the persistent signing key instead of generating a fresh
  ephemeral key, runs tests/lint, and uses version-specific release notes.

### Earlier candidate validation (2026-10-19)

- Debug and unsigned release APK builds, Android lint and 14 regression tests pass.
- Emulator DFP/1 checks pass for multi-file receipt (including empty files),
  cross-page prompts, decline, invalid metadata, changed file headers, checksum
  failure, sender cancellation and concurrent requests.
- Physical Android ↔ macOS/Windows transfers, Android 8/9 storage, and signed
  Beta 1 → Beta 2 installation remain release gates. No release was published.

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

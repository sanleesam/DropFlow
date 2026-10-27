# Desktop dev.8 device discovery audit

Date: 2026-10-27. Scope: desktop discovery, its settings and UI integration,
the handoff from a discovered peer to TCP transfer, and the Development release
pipeline. This is not a complete security audit or certification of Android,
Windows installation, every network topology, or every transfer scenario.

## Current outcome

The reported failure was reproduced: the installed Mac Development 7 client
showed no nearby devices while a Windows Beta 17 client was open on the same
network. An unchanged Mac client restart restored detection. The Mac UI then
showed Windows device **Susserga**, and the user confirmed that Windows showed
**Sanlee’s MacBook Pro**.

The code changes address concrete recovery and metadata defects. The exact
event that put the original Mac daemon into the failed state is **not known**.
Restart recovery alone does not prove that a failed multicast join, system
sleep, permissions, or any single library defect caused that initial state.

Automated validation and the native Mac bundle build passed. The first dev.8
launch reproduced an empty browser; its new Refresh action recovered Susserga
in about 2.3 seconds. The final build adds one automatic startup retry after
five seconds when the peer list remains empty. A subsequent cold launch detected
Susserga in about 1.5 seconds without a manual action. The dev.8 tag and hosted macOS/Windows build both completed successfully.
Remaining live checks are recorded below.

## Evidence from the reported failure

| Observation | What it establishes | What it does not establish |
| --- | --- | --- |
| Installed Mac `0.1.0-dev.7` showed zero peers | The user-visible failure was reproduced | Its exact trigger or duration |
| Native `dns-sd` resolved Windows **Susserga**, `0.1.0-beta.17`, with valid DFP/1 metadata and IPv4/IPv6 addresses | The Windows service was advertised and reached the Mac’s native discovery service | That DropFlow’s existing daemon received or processed the same packets |
| A small standalone Rust probe using the old `mdns-sd 0.20.1` resolved Windows | The library could discover this peer in a fresh process on this network | That the long-running installed app had healthy sockets, cache or subscriptions |
| Restarting the unchanged Mac application restored the Windows peer in its UI | Reinitializing the app recovered discovery | Which part of reinitialization was decisive |
| User confirmed Windows then saw the Mac | Baseline detection worked in both directions after restart | Post-fix detection or file integrity |
| All displayed DropFlow Local Network permission entries were on | No displayed denial explained the test at inspection time | That every historical/ad hoc code identity had consistent permission state |

Wi-Fi, routing, DNS, VPN, firewall and permission settings were not changed.
The permission page was inspected read-only. No temporary peer advertisement,
address injection or forwarding bridge was used for these Mac/Windows discovery
observations.

A user-initiated Mac dev.7 → Windows transfer of 24,129,536 bytes also completed
with final `ACK_RECEIVED` during the baseline check. Its content was not opened
or separately hashed, so this is an acknowledged transfer result, not an
independent saved-file integrity check.

Local diagnostic evidence from this session is under
`/tmp/dropflow-discovery-audit/`, including `probe-0201.log`,
`installed-restart.log` and automated-check logs. A retained copy of the key logs is also in the gitignored
`apps/desktop/src-tauri/target/dev8-audit-evidence/` in this worktree. These local
evidence files are not published with the source.

## Discovery pipeline reviewed

1. Persisted UUID and device settings initialize the Rust backend.
2. The frontend installs `peer-discovered` and `peer-lost` listeners before
   invoking discovery, then reconciles the current peer snapshot using an event
   revision guard.
3. `MdnsDiscoveryEngine` advertises `_dropflow._tcp.local.`, browses services and
   resolves TXT records, service port and scoped addresses.
4. Metadata validation rejects self identity, mismatched UUIDs, unsupported DFP
   versions, invalid names/types, zero ports and services without usable
   addresses. Loopback, multicast and unspecified peer addresses are filtered.
5. `PeerRegistry` maps service instances to peer identities and emits additions,
   updates and removals to the frontend.
6. File sending chooses a discovered endpoint and hands it to the existing
   DFP/1 TCP transfer implementation.

No defect was proven in the listener-before-start ordering or revision-guarded
initial snapshot. The DFP/1 frame format and transfer architecture were preserved.

## Changes in dev.8

| Finding | Before | Change | Evidence/status |
| --- | --- | --- | --- |
| Refresh was ineffective once browsing started | `retryDiscovery` called idempotent `start_discovery`, which returned when already running | Dedicated `restart_discovery` replaces the daemon and recreates the browse path | Native UI recovered Susserga in about 2.3 seconds |
| Daemon replacement could retain stale peer records | New daemon had a fresh cache but the application retained the old registry | Clear stale registry entries and emit peer removals when the old browse channel closes | Regression test verifies stale peers and service mappings are discarded |
| Monitor attachment could bind to a later daemon during concurrent replacement | Monitor setup reacquired the current shared daemon | Capture each daemon’s monitor receiver before publishing it | Source confirmed; regression review completed |
| Recovery could fail silently | Replacement logged errors without returning them | Propagate restart failures to the caller; retry network-transition recovery after a failed replacement | Source confirmed |
| Empty discovery did not recover when returning to the app | Focusing the app retained the same empty daemon state | One automatic startup retry after five seconds if empty; refresh on focus when no peer is present and the previous refresh is older than 30 seconds | Source confirmed; focus/wake retest pending |
| Rename could publish invalid type metadata | Backend `save_settings` advertised literal `Desktop`, while peer validation accepts lowercase `desktop` | Remove duplicate backend advertisement side effect; the frontend owns the actual type and visibility | Source confirmed |
| Renaming a hidden device could reveal it | Duplicate backend advertisement ignored visibility | Same removal preserves the frontend’s port-zero unregistration path | Source confirmed; setting persistence is a separate open issue below |
| Requested debug diagnostics were suppressed in release builds | `RUST_LOG` set the global level but `enabled()` still capped output at Info | Store and honor the resolved logger filter | New logging regression test passes |
| Dependency lacked published recovery fixes | Locked `mdns-sd 0.20.1` | Upgrade to `0.21.6` | Locked build/tests pass; exact original trigger remains unproven |
| Mac bundle omitted local-network purpose text | No app-specific usage description or Bonjour service declaration | Add `Info.plist` entries for the local sharing purpose and `_dropflow._tcp` | Purpose text verified in the built app bundle |

Relevant code: `apps/desktop/src-tauri/src/device_discovery/mod.rs`,
`apps/desktop/src/session/DropFlowProvider.tsx`,
`apps/desktop/src-tauri/src/state_manager/mod.rs` and
`apps/desktop/src-tauri/src/logging.rs`.

The [upstream changelog](https://github.com/keepsimple1/mdns-sd/blob/main/CHANGELOG.md)
documents fixes included in 0.21.6: retrying failed multicast joins and avoiding
event-backpressure deadlocks; earlier included versions also fix sleep-related
cache expiry and repeated resolution of unresolved service instances. These are
relevant recovery improvements, not proof of the original failure’s cause.

Apple’s [local network privacy guidance](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)
supports providing a local-network purpose description and using a stable
Apple-issued signing identity. The installed dev.7 Mac binary was ad hoc/linker
signed, with no Apple team identity and no sealed app resources. dev.8’s usage
string does not itself create a stable Developer ID signature or guarantee
permission continuity across builds.

## Confirmed remaining issues

Priorities describe impact: P1 blocks a supported path; P2 causes a bounded
reliability or settings defect. These findings are source-confirmed, but were
not all reproduced with physical devices in this session.

### P1 — IPv6-only transfer handoff is invalid; endpoint fallback is absent

`DropFlowProvider.tsx` selects only `targetDevice.addresses[0].address`
(`sendSelected`, line 400) and discards its interface scope.
`transfer_manager/sender.rs:83–91` formats the endpoint as `ip:port` and parses it
as a `SocketAddr`. An IPv6 literal requires brackets; a link-local IPv6 address
also needs its interface scope. The current formatting therefore fails for an
IPv6-only discovered peer before connecting.

The desktop receiver also binds only `0.0.0.0:0`
(`transfer_manager/receiver.rs:81`), while automatic advertisement includes IPv6
addresses. Correcting sender formatting alone would not make the desktop
receiver reachable over IPv6.

For multiple advertised IPv4 addresses, `resolved_addresses` sorts lexically,
and sending attempts only the first entry. A detected peer can therefore fail
to transfer when the first endpoint is unreachable even if another address is
usable. The presence of multiple addresses is not evidence that fallback works.

Follow-up: align advertised addresses with the actual receiver, pass
structured/scoped candidates to Rust, construct socket addresses correctly,
try viable alternatives within a bounded connection budget, and add IPv6-only
plus multi-interface tests. This was not the zero-peer failure seen in
the baseline: that failure occurred before TCP endpoint selection.

### P2 — Hidden-device preference does not survive relaunch

`SettingsProvider.tsx` defaults `deviceVisibility` to true, but its
`save_settings` payload and reload mapping omit that field. Rust
`state_manager::UserSettings` also has no visibility field. The UI can hide the
service during the current session, but relaunch restores visibility.

Follow-up: persist visibility with an explicit backward-compatible default,
load it before advertising, and verify hidden relaunch and rename behavior.

### P2 — Rename accepts names that peers reject

`DevicesPage.tsx:282–290` and `SettingsPage.tsx:154–157` save input without the
protocol’s byte/control-character constraints. `validate_peer_metadata` rejects
empty names, names over 64 UTF-8 bytes and names containing control characters.
A name accepted locally can therefore make the device invisible to peers.

Follow-up: validate names before save/advertisement, provide a useful UI error,
and test multibyte Unicode boundaries. A character-count-only HTML limit would
not enforce the UTF-8 byte limit.

### P2 — Approval and streaming timeouts are not separated

`transfer_manager/sender.rs:95–97` sets a 15-second socket read timeout, and
`sender.rs:132–156` waits for acceptance under that timeout. The desktop receiver
allows 60 seconds for authorization (`receiver.rs:234`). A sender can therefore
disconnect while the recipient’s approval prompt is still available.

This mismatch was reproduced in earlier Mac/Android emulator testing; the
current Mac/Windows discovery observations do not retest delayed acceptance.
Follow-up: use a bounded approval timeout consistent with the prompt, restore
the streaming timeout after acceptance, and test delayed accept/decline.

### P3 — Mac disk-space status stays in a loading state

The live Mac views repeatedly displayed `Checking this disk`. In
`fs_browser.rs`, `disk_usage_of` explicitly returns an error on non-Windows
platforms. `useDiskUsage` turns that error into `null`, which the UI also uses
for its initial loading state. This makes an unsupported operation look as if
it is still running.

Follow-up: implement disk usage on supported Mac filesystems or show an explicit
unavailable state. This does not affect the mDNS detection fix.

## Development release audit

Previously, a `v0.1.0-dev.8` tag alone would not run the Development workflow.
It accepted main pushes/manual runs and derived every version from the run
number. dev.8 adds:

- Explicit `v*-dev.*` tag support with strict canonical version validation.
- One resolved version shared by source metadata, build environment, release
  notes, platform manifests, merged manifest and stale-asset cleanup.
- Frontend regression/build and locked Rust-test gates before publication.
- Tests for tag version resolution, including dev.8 overriding the run number.

The floating `development` updater release, macOS ARM64/Windows x64 matrix,
signed updater-entry checks, merged manifest and upload-before-prune order are
preserved. Beta and Android release triggers remain independent. An updater
signature is distinct from Apple Developer ID signing or notarization.

The fix is isolated from the unpushed Android beta.2 redesign branch. The
pre-existing `TASKS.md` deletion in the original checkout was left untouched.

## Validation record

| Check | Verified result |
| --- | --- |
| Baseline failure reproduced | Passed: Mac dev.7 zero peers while Windows service was resolvable |
| Unchanged-app restart recovery | Passed: Mac UI detected Susserga; user confirmed Windows detected Mac |
| Rust tests | 51 passed, including daemon refresh/old-browser shutdown, stale-registry reset and logger-filter regressions |
| Frontend checks | 31 passed: 10 lifecycle and 21 primitive/UI checks |
| Frontend production build | Passed |
| Development version resolver | 5 tests passed |
| Development workflow syntax | Parsed locally; tagged dev.8 hosted workflow completed successfully on macOS and Windows |
| New native application build | Local macOS ARM64 bundle and hosted Mac build passed; published updater signature verified cryptographically |
| Post-fix automatic Mac → Windows discovery | Passed: local dev.8 cold launch in about 1.5 seconds; downloaded CI dev.8 installed in Applications detected Susserga in about 3.4 seconds |
| Post-fix Windows → Mac discovery | Baseline user confirmation covers dev.7 ↔ Beta17; Windows-side display of the final dev.8 install was not independently confirmed |
| Native Refresh and stale-peer cleanup | Two native Refresh checks recovered Susserga in about 2.3 and 1.3 seconds; registry-reset regression passed |
| Real Mac/Windows file transfer | Baseline user-initiated transfer acknowledged; generated dev.8 requests connected and handshook, but no acceptance/final ACK was recorded; round-trip integrity remains unverified |
| Natural sleep/wake and network-transition reproduction | Not covered; no live network settings changed |
| dev.8 tag and hosted builds | `v0.1.0-dev.8` published at `6ab7ad9`; macOS ARM64, Windows x64 and combined-manifest jobs passed in run `40457006305` |

The downloaded Mac archive was verified with `minisign-verify` against the
public updater key checked into `tauri.conf.json`. Its bundle version is
`0.1.0-dev.8`, and its network purpose description is present. The installed
`/Applications/DropFlow.app` executable matches the downloaded CI executable
(SHA-256 `03cd2b27a43fd56a6b3f48dc24bfb69b34e408a706eaa883641a19125f943c15`).
The earlier dev.7 app was backed up locally before replacement. The CI app is
still ad hoc/linker signed; updater signature verification does not establish
Apple Developer ID signing or notarization.

Release links: [dev.8 source tag](https://github.com/sanleesam/DropFlow/tree/v0.1.0-dev.8),
[completed workflow](https://github.com/sanleesam/DropFlow/actions/runs/40457006305),
and [Development downloads](https://github.com/sanleesam/DropFlow/releases/tag/development).
The existing floating release kept its older body despite successful asset
publication. It was corrected manually to dev.8, and a follow-up workflow step
now refreshes release notes after the merged manifest is uploaded.

The final merged `latest.json` advertises `0.1.0-dev.8` for both
`darwin-aarch64` and `windows-x86_64`. Both the Mac archive (7,403,116 bytes)
and Windows installer (4,984,138 bytes) passed cryptographic updater-signature
verification against the checked-in public key. The final floating release
contains only the dev.8 installers, current archive/signature, and manifest.
The Windows installer was downloaded for verification, not installed or run.

## Limits and release interpretation

Successful discovery does not prove successful file transfer, and a successful
transfer does not prove every interface, timeout, cancellation or sleep case.
Post-fix live testing should retain the peer names/versions, restart/refresh
outcomes, transfer acknowledgement and saved-file hash evidence.

Development 8 is a targeted discovery recovery release. Its publication should
not be presented as resolving all remaining transport/settings defects or as
making Android beta.2 ready for release. Physical Android discovery, Windows
installation/upgrade, IPv6-only transfer, original-key Android upgrades and
other previously unverified platform cases require their own evidence.

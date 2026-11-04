# Desktop 0.1.1-beta.18 validation — 2026-11-04

## Scope and findings

Desktop only. The existing Android redesign branch is preserved separately;
its changes are not folded into this desktop release. The unrelated local
TASKS.md deletion is preserved and excluded from commits.

Fixed confirmed findings: folders rejected as non-files, premature approval
timeout, counter-only updater ordering, hard-coded Development core version,
ignored receive-directory preference, success acknowledgement before file
promotion, unvalidated approved-vs-received file headers/completion sessions,
symbolic-link receive-path escapes, rename overwrites, IPv6 socket formatting,
lost failed selections and perpetual unsupported disk-usage loading state.

## Executed checks

- Frontend TypeScript check, production build, UI primitive checks (21
  assertions), and existing transfer-session lifecycle suite passed.
- Version tooling tests (3 cases), four-manifest consistency, and Ruby YAML
  parsing of both desktop workflows passed.
- Rust library tests cover discovery metadata/identity, transfer framing,
  path sanitization, folder expansion, state/history persistence, update
  ordering, channel-generation races, power guards and file-browser CRUD.
- Native macOS debug app built with a separate validation bundle identifier.
  About reports `0.1.1-beta.18 · beta`. Devices, Transfers, Files and Settings
  navigation smoke checks passed without changing the installed app.
- Two native sends to a loopback DFP/1 peer passed: a nested folder with spaces,
  Unicode text, a zero-byte file and a 1 MiB binary. Both waited 20 seconds for
  approval and verified exact bytes/SHA-256. JSON final ACK compatibility was
  exercised, in addition to the native receiver's plain-text final ACK.
- Seven native receiver cases passed using a disposable configured receive
  directory: valid 1 MiB file, bad checksum, sender cancel, socket disconnect,
  unapproved header, wrong completion session and traversal path. Failed cases
  did not publish final files or retain partial files.
- Incoming prompt appeared over Settings. The native Decline button rejected
  a synthetic request. A separate unattended request timed out and rejected.
- Synthetic DNS-SD discovery correctly rejected an invalid protocol version,
  then accepted the same synthetic peer with `version=DFP/1` (native app log).

`scripts/desktop-transfer-probe.py` reproduces the loopback peer checks. It is
test-only tooling, not bundled into releases. It prints exact fixture paths and
ports; trigger the existing native send_files command for the receive mode.

## Not established by these checks

- Physical Mac↔Windows or Android LAN discovery, Wi-Fi roaming/firewall/VPN
  combinations, Windows UI interaction, large multi-GB files and all supported
  OS versions need separate hardware acceptance.
- A signed updater artifact is not Apple notarization or Windows Authenticode.
- User settings, received files and history in the installed app were not
  cleared or replaced. OS/network/accessibility settings were not changed.
- Empty directories/symlinks are not sent. Directory requests remain subject to
  DFP/1's existing 64 KiB metadata limit. Approval-time cancellation is not
  instantaneous while the sender is waiting for the peer's response.
- File delivery is not an atomic multi-file filesystem transaction. A disk
  failure during promotion may leave already verified completed files; the
  transfer reports failure rather than acknowledging complete success.

This evidence supports a beta fix release, not a claim that every possible
device, network, filesystem race or security scenario is certified.

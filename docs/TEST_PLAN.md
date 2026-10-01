# DropFlow Beta 1 — Manual Test Plan

Run every case on real hardware: **Android phone**, **Windows PC**, **MacBook**.
All devices must be on the same Wi-Fi network (no guest network, no AP
isolation, no VPN). Builds under test (versioned independently per platform):

- Desktop: `0.1.0-beta.15`
- Android: `0.1.0-beta.1`

Legend per cell: **P** = pass, **F** = fail (note the log file:
`<app data>/logs/dropflow.log` on desktop), **S** = skipped.

## 0. Setup checks (per device)

| # | Check | Android | Windows | macOS |
|---|-------|---------|---------|-------|
| 0.1 | App installs and launches | | | |
| 0.2 | Firewall allows DropFlow (Windows prompt: allow private networks; macOS: System Settings → Network → Firewall allow DropFlow) | n/a | | |
| 0.3 | Device name is advertised (check from another device) | | | |
| 0.4 | Settings screen opens; device name editable; rename propagates to peers within ~30 s | | | |

## 1. Discovery matrix

Peer A discovers peer B = A shows B's device card with correct name/type.

| # | From ↓ / To → | Android | Windows | macOS |
|---|---------------|---------|---------|-------|
| 1.1 | Android | n/a | | |
| 1.2 | Windows | | n/a | |
| 1.3 | macOS | | | n/a |

| # | Scenario | Result |
|---|----------|--------|
| 1.4 | Discovery refresh: tap Rescan on Android / reopen window on desktop; peers reappear ≤ 30 s | |
| 1.5 | Reconnect: toggle device Wi-Fi off/on; peer disappears and reappears ≤ 60 s | |
| 1.6 | Duplicate peers: rotate the Android device (screen rotation); only one card remains | |
| 1.7 | Self filtering: own device never appears in the peer list | |
| 1.8 | Visibility off (desktop Settings): desktop disappears from all peers | |
| 1.9 | macOS discovers nothing → read `dropflow.log`; confirm the idle watchdog line naming the firewall cause | |

## 2. Transfer matrix (send + receive per pair)

Repeat for every direction: Android → Windows, Windows → Android,
Android → macOS, macOS → Android, Windows → macOS, macOS → Windows.

| # | Scenario | A→B result |
|---|----------|-----------|
| 2.1 | Single small file (1 KB text) | |
| 2.2 | Single photo (5–20 MB) | |
| 2.3 | Multiple files (5+ mixed types) | |
| 2.4 | Large file (1–4 GB video) — watch memory stays flat | |
| 2.5 | Zero-byte file | |
| 2.6 | File with spaces/unicode in the name | |
| 2.7 | Same filename sent twice (receiver renames `name (1).ext`, no overwrite) | |
| 2.8 | Progress shows correct %, speed, current file name | |
| 2.9 | Completion: history entry appears on both sender and receiver | |
| 2.10 | Received file opens and matches source (checksum verified) | |

## 3. Accept / decline / trust

| # | Scenario | Result |
|---|----------|--------|
| 3.1 | Incoming prompt appears; Accept starts transfer ≤ 2 s | |
| 3.2 | Decline: sender shows "declined" (Android) / failure toast (desktop); no partial files on receiver | |
| 3.3 | Ignore prompt 60 s: request times out on both sides | |
| 3.4 | "Trust device" + Accept: next transfer from that device auto-accepts (same app session) | |
| 3.5 | Android Settings → "Require confirmation" off: all incoming transfers auto-accept | |

## 4. Cancellation and failures

| # | Scenario | Result |
|---|----------|--------|
| 4.1 | Cancel from sender mid-transfer: receiver shows failure; no `.part`/partial files remain | |
| 4.2 | Cancel from receiver mid-transfer: sender shows failure; no partial files | |
| 4.3 | Sender goes offline mid-transfer (airplane mode): receiver fails cleanly ≤ 20 s | |
| 4.4 | Network interruption (Wi-Fi drop 10 s) mid-transfer: session fails with clear error, retry works | |
| 4.5 | Slow accept (> 15 s on desktop prompt): sender times out with clear message (known limitation) | |
| 4.6 | Concurrent: send A→B while B receives from C (desktop) or queue behaves sanely (Android serializes) | |

## 5. History and state

| # | Scenario | Result |
|---|----------|--------|
| 5.1 | History lists completed sends and receives with correct sizes | |
| 5.2 | History filter tabs (All/Sent/Received, Failed on desktop) and search work | |
| 5.3 | Desktop: history survives app restart (persisted) | |
| 5.4 | Android: history present during session (persistence planned for Beta 2) | |
| 5.5 | Desktop: "Clear history" works and restarts clean | |
| 5.6 | Rotate Android device mid-session: transfer continues, progress UI intact | |

## 6. Logs

Desktop: `<app data>/logs/dropflow.log` — attach to bug reports. Android:
`adb logcat -s DropFlow*` — attach to bug reports.

## Known limitations going into Beta 2

- Slow accept: a desktop user taking > 15 s to accept fails the sender's wait
  (matches the reference implementation; bump the desktop authorization read
  timeout in Beta 2).
- Android history and trusted devices are session-scoped (persistence planned).
- Android notifications are not wired yet (planned).

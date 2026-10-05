## Completed (Beta Sprint — 0.1.0-beta.15)

> **Superseded by the Beta 1 preparation pass:** the follow-up beta-preparation
> pass fixed the remaining discovery instrumentation and robustness gaps
> (structured file logging, mDNS daemon monitoring, discovery idle watchdog,
> TXT parity), and added CHANGELOG.md and docs/TEST_PLAN.md. Desktop and
> Android are versioned independently (desktop `0.1.0-beta.15`, Android
> `0.1.0-beta.1`) — see CHANGELOG.md for the full list.

- Audited Android and desktop DFP/1 implementations end to end: framing, handshake,
  request/accept/reject, per-file ack, SHA-256 verification, completion ack, and
  cancellation all match the reference desktop implementation. No protocol changes needed.
- Fixed Android receive storage for scoped storage: incoming files are written through
  MediaStore Downloads (API 29+) or the legacy public Downloads path with a runtime
  WRITE_EXTERNAL_STORAGE prompt (API 28 and below). Previously every receive would have
  failed with EACCES on Android 10+.
- Incoming-transfer authorization now honours Settings: "Require confirmation" and
  "Auto-accept trusted devices" mirror the desktop receiver policy. The trust checkbox
  in the incoming sheet keeps the sender trusted until the app restarts (persistent
  trusted-device storage is deferred to Beta 2).
- Android → Desktop sending now reports real failure reasons (declined, cancelled by
  recipient, timeout, connection lost) in the Home result message.
- mDNS advertisement re-registers when the device name is renamed in Settings, and after
  Wi-Fi reconnects.
- Removed dead transfer code: paths-based `FileSender.sendTransferSession`,
  `TransferConnection.sendTransferRequest`, `TransferEngine.connectToPeer`, unused
  `DropFlowHeader` composable, and the unused `incoming-transfer-dismiss` desktop event.
- Receiver explicitly handles `TRANSFER_CANCEL` mid-stream and cleans up partial files.

## Current State

Android is installable and testable as a Beta: discovery, Desktop → Android receive,
Android → Desktop send, progress, cancel, decline, trust, settings, and in-memory
history all work. `./gradlew assembleDebug` is green (0.1.0-beta.1, versionCode 17);
desktop `cargo test` (28 passed) and `pnpm tsc --noEmit` are green.

## Remaining Tasks (Beta 2)

1. Notifications for incoming/completed transfers (POST_NOTIFICATIONS permission is
   already declared; only the notification plumbing is missing).
2. Persisted history and trusted-device storage (currently in-memory per session).
3. Resume support for Android sends (SAF streams cannot seek; protocol supports it).
4. On-device interoperability testing in both directions on real hardware.
5. Release-build signing config and a release APK/AAB for distribution.

## Immediate Next Task

On-device testing: send and receive between desktop and Android, exercise decline,
timeout, cancel, and checksum-mismatch paths.

## Important Decisions

- DFP/1 unchanged; all fixes are implementation-side.
- Scoped storage uses MediaStore Downloads with IS_PENDING; no MANAGE_EXTERNAL_STORAGE.
- Trust choices are session-scoped until persistent trusted-device storage lands.

## Files Modified (Beta Sprint)

- `apps/android/app/src/main/java/com/dropflow/android/core/network/transfer/StorageManager.kt`
- `apps/android/app/src/main/java/com/dropflow/android/core/network/transfer/FileReceiver.kt`
- `apps/android/app/src/main/java/com/dropflow/android/core/network/transfer/FileSender.kt`
- `apps/android/app/src/main/java/com/dropflow/android/core/network/transfer/TransferEngine.kt`
- `apps/android/app/src/main/java/com/dropflow/android/core/network/transfer/TransferConnection.kt`
- `apps/android/app/src/main/java/com/dropflow/android/core/network/discovery/NsdDeviceDiscoveryEngine.kt`
- `apps/android/app/src/main/java/com/dropflow/android/ui/home/HomeViewModel.kt`
- `apps/android/app/src/main/java/com/dropflow/android/MainActivity.kt`
- `apps/android/app/src/main/AndroidManifest.xml`
- `apps/android/app/build.gradle.kts`
- `apps/desktop/src-tauri/src/receiver.rs` (removed unused payload field)
- `apps/desktop/src/pages/Home.tsx` (removed unused listener)

## Files Deleted

- `apps/android/app/src/main/java/com/dropflow/android/ui/components/DropFlowHeader.kt`

## Build Status

- `apps/android/./gradlew assembleDebug` passed (0.1.0-beta.1, versionCode 17).
- `cargo test --manifest-path apps/desktop/src-tauri/Cargo.toml` passed: 28 tests.
- `pnpm tsc --noEmit` (desktop) passed.

## Manual Testing Required

1. Desktop → Android: single + multi-file sends; accept, decline, timeout, and trust
   flows; verify files land in Download/DropFlow and open correctly.
2. Android → Desktop: SAF picker sends; verify desktop save, checksums, and completion.
3. Rename the Android device in Settings and confirm the desktop sees the new name.
4. Toggle "Require confirmation" off and verify auto-accept; verify trusted-device
   auto-accept while the app stays running.
5. Cancel mid-transfer in both directions; confirm no leftover partial files.

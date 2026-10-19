# Real Mac ↔ Android emulator testing — 2026-10-19

## Outcome

**Real-app file transfers passed in both directions using temporary test-only
address entries. Normal automatic discovery is still unresolved in this setup.**

| Check | Result |
| --- | --- |
| Android SAF picker → Mac, text file | Passed; normal Mac accept prompt; exact bytes and SHA-256 |
| Android → Mac, 1 MiB binary + empty file | Passed; both files and hashes verified |
| Mac picker → Android, text file | Passed; normal Android accept prompt; exact bytes and SHA-256 |
| Mac → Android, 1 MiB binary + empty file | Passed; both files and hashes verified |
| Decline Android request on Mac | Passed; Android reported `Transfer declined by recipient` |
| Mac → Android, acceptance delayed about 15 seconds | Failed; sender closed before Android acceptance |
| Normal automatic discovery | Did not connect the apps |
| Real-app cancellation/disconnect and physical-device discovery | Not covered by this session |

There were six delivered files across four successful transfer sessions. The
Mac's logs include final `ACK_RECEIVED` for Mac → Android; Android's sender reports
successful completion for Android → Mac. Hashes were checked against actual saved
files, not inferred from progress UI. Android unit tests (14), lint and release
assembly also passed after adding the test support. No release was published.

## Builds and test setup

- Android Beta 2 preview: `0.1.0-beta.2`, versionCode 18.
- Installed Mac app: `/Applications/DropFlow.app`, `0.1.0-dev.7`.
- Android Emulator 37.1.11, Pixel 9, virtual address `10.0.2.16`.
- Mac receiver used its existing listener. No desktop production code was changed.

A separate instrumentation test APK supplied the Android ViewModel with a
clearly named test peer at the emulator's host alias. File selection, sending,
DFP/1, receiving and saving ran through the actual app implementations. The first
single-file transfers used normal UI accept controls. For the Mac multi-file
success case, the test fixture invoked the same authorization method immediately
for the known Mac sender UUID, avoiding the observed short sender timeout. It did
not persist trust or change the app's confirmation preference.

For the reverse direction, a temporary host bridge accepted only same-Mac client
addresses and forwarded bytes to the emulator-local TCP mapping. Explicitly named
DNS records supplied a test peer to the Mac app over loopback. That is a device
fixture, not evidence that normal LAN discovery works.

Android documents its emulator-managed `10.0.2.*` network and the `10.0.2.2` host
alias in [Network address space](https://developer.android.com/studio/run/emulator-networking-address).
Emulator-to-emulator NSD support in newer releases does not establish host/LAN
interoperability; see [Interconnecting emulator instances](https://developer.android.com/studio/run/emulator-networking-interconnect).

Host Wi-Fi, firewall, VPN, DNS and routing settings were not changed. A host packet
capture was unavailable because the current account lacked capture permission; no
privilege or firewall changes were attempted. Test entries and forwarding were
removed, and the instrumentation test APK was uninstalled after testing.

## Findings requiring follow-up

1. **Automatic discovery:** both real UIs reported zero peers. Android saw its own
   service only, including after rescan. Host Bonjour saw both service names, but
   the Mac service resolved to loopback during the test. An ordinary host Bonjour
   proxy advertisement did not make the guest discover it. There is not enough
   evidence to assign one root cause or blame Android production discovery.
2. **Approval timeout mismatch:** the installed Mac sender times out after roughly
   15 seconds while Android keeps its incoming authorization prompt available for
   up to 60 seconds. Accepting after the sender closes produces an EOF failure.
   Current desktop source sets a 15-second read timeout; Android uses the same
   15-second socket read limit. A separate authorization timeout, followed by the
   normal streaming timeout, should be implemented and tested before release.
   The promptly accepted transfers succeeded; these are separate outcomes.

These tests used desktop Development 7. They do not validate desktop Beta 17,
Windows, real Android Wi-Fi discovery, Android 8/9 storage, signed upgrades or
background receiving.

## Reproduce the address fixture

In `apps/android`:

```sh
./gradlew -PdropflowInteropRunner=com.dropflow.android.InteropInstrumentation assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e peerHost 10.0.2.2 -e peerPort <Mac-receiver-port> -e peerId <Mac-UUID> com.dropflow.android.test/com.dropflow.android.InteropInstrumentation
```

Default decisions are manual. `-e decision accept` or `decline` drives the normal
ViewModel authorization methods only for the supplied Mac UUID. Stop via
`adb shell run-as com.dropflow.android touch files/stop-interop-fixture`.

For a Mac-to-emulator fixture, create an owned local forwarding entry, then run:

```sh
adb forward tcp:44210 tcp:42100
python3 scripts/android-emulator-mac-bridge.py --host <this-Mac-LAN-IPv4> --device-id <Android-UUID>
```

Stop the bridge and remove its mapping after testing:
`adb forward --remove tcp:44210`. This test tooling is excluded from release APKs.

Local evidence is in `apps/android/build/mac-interoperability/`: verified hash
results, generated input files, saved transfer logs and an Android result capture.
Received copies were retained under the normal DropFlow download folders for review.

## UI correction

The nonfunctional Titanium/Icy Blue labels and desktop-comparison text were
removed from Android Appearance settings in commit `359ccbe`. The working Reduce
animations control remains.

# Mac ↔ Android emulator test attempt — 2026-10-19

## Builds used

- Android: Beta 2 preview, `0.1.0-beta.2`, versionCode 18.
- Installed Mac app: `/Applications/DropFlow.app`, `0.1.0-dev.7`.
- Android emulator: Pixel 9, virtual address `10.0.2.16`, receiver port 42100.

## Result

**Blocked at automatic discovery; no real Mac ↔ Android transfer passed.**

- Both real apps started and exposed their receiver listeners.
- Android discovery reported its own service, but not the Mac service, including
  after a rescan. The Mac UI continued to report zero nearby peers.
- The host's Bonjour browser saw both advertised service names.
- The Mac service resolved through host Bonjour to `127.0.0.1` during this test.
  The running Mac receiver listened on all IPv4 interfaces on its chosen port.
- A temporary, separately named Bonjour advertisement for the Mac listener's LAN
  endpoint did not make the guest discover it. It was removed after the attempt.

These observations do not establish a single root cause: the installed Mac build
and emulator multicast/network isolation both need to be considered. This did
not test desktop Beta 17 or a physical Android device. Host Wi-Fi, firewall, VPN,
DNS and routing settings were not changed. No production discovery code was
modified and no release was published.

## UI correction during the test

Removed the nonfunctional Titanium/Icy Blue labels and desktop-comparison text
from Android Appearance settings. The actual Reduce animations control remains.
The updated preview APK was rebuilt, installed and visually checked in the
emulator. This correction is commit `359ccbe`.

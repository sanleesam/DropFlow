# DropFlow transfer-engine lab

Experimental branch: `codex/transfer-engine-lab`. No release, default Nearby
mode, changes to saved network settings, or new production listener.

## What the research supports

- [Apple Network framework](https://developer.apple.com/documentation/technotes/tn3151-choosing-the-right-networking-api)
  exposes Apple's peer-to-peer link to native Apple apps. That's the first
  supported no-network-switch prototype, for Mac ↔ Mac. The
  [framework migration guide](https://developer.apple.com/documentation/technotes/tn3213-moving-from-multipeer-connectivity-to-network-framework)
  also warns that peer-to-peer activity can compete for radio time.
- [Tarish](https://tarish.dev/) describes a real Android AirDrop reimplementation
  but uses a system image, privileged daemons, and a vendor Wi-Fi driver. Its
  published results report that its tested phones drop their normal Wi-Fi
  association during AWDL use. Its linked app source was not publicly
  retrievable in this audit. [OWL](https://github.com/seemoo-lab/owl) requires
  a card with monitor mode and frame injection. Neither is a normal DropFlow
  Android APK solution.
- [Quick Share's AirDrop support](https://blog.google/security/android-quick-share-support-for-airdrop-security/)
  is evidence of system-level interoperability, not a transferable third-party
  API. [Android Wi-Fi Aware](https://developer.android.com/develop/connectivity/wifi/wifi-aware)
  is promising for supported Android pairs, but [Apple DTS](https://developer.apple.com/forums/thread/827887)
  says native macOS does not support it. The local macOS 27.2 SDK also marks
  `WACapabilities` unavailable on macOS. No approved Android ↔ Mac direct path
  that keeps both primary links up was established.
- [PairDrop](https://github.com/schlagmichdoch/PairDrop/blob/master/docs/technical-documentation.md)
  uses WebRTC and can use internet relays for cross-network pairs. That's a
  different product mode. [LocalSend's protocol](https://github.com/localsend/protocol)
  demonstrates separating approval from binary streaming, but does not create
  an independent radio link. No third-party source was copied into DropFlow.

## Implemented on this branch

1. **DFP/1-compatible bulk I/O.** Mac/Windows Rust and Android Kotlin now send
   256 KiB data frames using reusable storage and one write for header plus
   payload. Receivers reuse bounded memory, accept the same frame types and
   length limits, and still hash the data and wait for final confirmation.
   The wire version and discovery are unchanged.
2. **Experimental batch v2 core.** The Rust feature `experimental-transfer`
   exposes a separate, transport-independent module for already-approved files.
   A single stream sends the batch without per-file round trips. Each block
   includes file index, offset, length and cumulative SHA-256. The receiver
   advances its checkpoint only after the storage callback succeeds. Restoring
   a checkpoint rehashes the staged prefix, and the sender rejects a changed
   source prefix. A matching batch receipt follows publication, not just receipt
   of bytes. Tests cover truncation/resume, corruption, storage failure, receipt
   mismatch, publication failure/retry, and a 64-file TCP loopback.
3. **Native Mac link probe.** Uses Network framework and binds outgoing discovery
   results to AWDL, rejecting ordinary LAN results. It moves only a fixed 32 MiB
   synthetic payload, checks SHA-256, times out after 45 seconds and never
   joins another network. The localhost self-test does not advertise a service.

The batch engine and native-link probes are **not wired together**. Batch v2 assumes
an authenticated encrypted stream, an approved manifest and an authenticated
resume-point exchange. There is no production TLS pairing, persistent checkpoint
journal, filesystem publication integration, Android batch v2 implementation,
or new production UI. The callbacks in the prototype define those boundaries;
they do not make staging durable or publication idempotent by themselves.

## What was measured

Optimized Rust build, 128 MiB synthetic memory-to-memory data, localhost TCP
with cancellation-aware sockets and SHA-256 at both ends. Five trials per
path, alternating run order:

| Desktop DFP/1 bulk path | Median |
| --- | ---: |
| Previous 64 KiB frames, split writes, fresh receive allocation | 498.3 MiB/s |
| Candidate 256 KiB frames, combined writes, reused receive buffer | 520.6 MiB/s |

That is **1.04× on this benchmark**, not a real-Wi-Fi result or a 10× claim.
It excludes file storage, radio, approval and per-file waits. Batch v2 removes
per-file waits from the data stream; its folder throughput still needs a
same-hardware comparison with storage and packet loss included.

Validation on this branch:

- 82 Rust tests passed with the experimental feature enabled; the separate
  optimized throughput test passed.
- 26 Android JVM tests, lint and debug/test APK assembly passed. A disposable
  emulator sent via the real file picker and received 256 KiB frames plus a
  short tail. File bytes, checksums, final acknowledgement, cancellation,
  collision handling and history persistence passed. The peers were test
  fixtures, not physical devices.
- The native Swift probe compiled and moved 32 MiB with a matching digest
  over `lo0`. AWDL itself was not validated; a second physical Mac is needed.

```sh
cargo test --locked --features experimental-transfer \
  --manifest-path apps/desktop/src-tauri/Cargo.toml --lib
cargo test --release --locked --manifest-path apps/desktop/src-tauri/Cargo.toml \
  --lib compare_bulk_throughput -- --ignored --nocapture
swiftc -swift-version 5 -O scripts/nearby-link-probe.swift -o /tmp/nearby-link-probe
/tmp/nearby-link-probe self-test
```

## Next hardware gate

Use two Macs already connected to different Wi-Fi networks. Keep an internet
stream active on both. With local-network permission granted if macOS asks,
run `/tmp/nearby-link-probe listen TEST-NAME` on one Mac and
`/tmp/nearby-link-probe send TEST-NAME` on the other. Use a unique test name
on a controlled network. The manual probe is plaintext and accepts one peer;
it is not suitable for real files. Confirm the AWDL interface, matching digest,
unchanged primary routes and continuing internet traffic. Repeat in reverse.
Only then attach authenticated pairing and the batch engine to the direct link.

## Windows ↔ Android Nearby Link probe

This is a standalone Windows host plus an Android lab screen. It
exchanges and verifies 16 MiB of synthetic data in each direction. No file
picker, production transfer, BLE discovery, cloud account, or relay is
involved. Credentials are temporary and entered locally for the hardware test;
they are not an internet pairing code.

- Windows requires an enabled WLAN interface. It checks BLE for diagnostics,
  but missing, disabled, or unreadable BLE uses the same manual SSID and
  passphrase flow. Neither path sends data over Bluetooth. Ethernet alone does
  not qualify. Passing `status` is a hardware preflight, not a guarantee of
  Wi-Fi Direct driver support.
- The Windows host uses the [classic Windows Wi-Fi Direct group-owner API](https://learn.microsoft.com/en-us/samples/microsoft/windows-classic-samples/wifi-direct-legacy/).
  It advertises a one-session WPA2 group, gets the peer endpoint from Windows,
  and opens TCP only on that endpoint’s local address for that peer. It stops
  on a detected loss of a pre-existing WLAN association or change of the
  original Internet adapter. [Microsoft’s driver model](https://learn.microsoft.com/en-us/windows-hardware/drivers/network/wdi-objects)
  describes concurrent station and group-owner operation, but real driver and
  firewall behavior must be verified before enabling the feature in-app.
- Android 12 or newer must report support for [concurrent local-only Wi-Fi](https://developer.android.com/reference/android/net/wifi/WifiNetworkSpecifier).
  The user may get an Android approval prompt for a **second** connection.
  The socket is bound to that connection only. The probe does not bind the
  process, change saved networks, or fall back to the default route. The
  request and socket are released on success, cancellation, failure, or
  timeout. A detected default-network change aborts the test.
- Mac ↔ Mac remains an independent AWDL probe. Native macOS ↔ Android and
  macOS ↔ Windows Nearby are not implemented. Use the existing shared-LAN
  transfer if the devices already have a common reachable network.

### Hardware run

The original Windows Development `0.1.2-dev.1` preview still requires BLE.
The Wi-Fi-only change is prepared for Windows Development `0.1.2-dev.2`.
It works with the existing Android Alpha `0.2.0-alpha.3` lab protocol; no
Android update is needed. In preview builds, open **Settings → Updates →
Open Nearby Link lab**. The Mac build and ordinary Beta releases omit the
lab. To test the Wi-Fi-only change from source:

1. On a Windows 10/11 test PC with a Wi-Fi Direct-capable adapter, run from the repo root:

   ```powershell
   cargo run --release --locked --manifest-path scripts/nearby-windows/Cargo.toml -- status
   cargo run --release --locked --manifest-path scripts/nearby-windows/Cargo.toml -- host
   ```

   Keep the one-time passphrase private. If firewall policy blocks the probe,
   report it; do not turn off the firewall or open the port to every network.
2. Build the Android debug APK with `:app:assembleDebug` and install it on a
   dedicated Android test phone. A debug APK cannot replace a differently
   signed production install; do not remove the production app to run the
   probe. Open the lab screen from **Settings → Updates**. The lab activity
   cannot be opened by other apps.

   Enter the host’s temporary credentials and approve Android’s secondary
   connection if prompted. Do not join the test SSID from system Wi-Fi settings.
3. Start with the two devices on different normal networks and monitor Internet
   traffic independently on both. Confirm both receipts, unchanged primary
   connectivity, and release of the temporary link. Repeat on representative
   Windows drivers and Android devices, including rejection, interruption,
   Android without secondary-STA capability, Windows with BLE absent or off
   (allowed), and Windows without Wi-Fi (rejected).
   Synthetic exchange and cross-compilation do not establish radio
   interoperability, throughput gains, or production readiness.

Before production: prove coexistence and byte integrity on hardware, handle
firewall and adapter errors in-app, authenticate peer identity and encrypt
the transport end-to-end, and integrate the existing transfer approval and
verified completion behavior. Keep the LAN path independent and do not
advertise the Mac cross-platform pairs as Nearby until a supported route exists.

Local validation: the Windows host type-checks for x64 and ARM64; its Rust
wire tests passed. Android debug assembly, lint, JVM tests, and release Kotlin
compilation passed. The lab activity and extra network permission are absent
from the default release manifest. The Android emulator reported no secondary
Wi-Fi support and correctly disabled the test button. No physical Windows ↔
Android radio session has been verified.

Measure current and candidate builds on the same physical pairs, both
directions, with a multi-GB file and a folder with hundreds of small files.
Include interruption, resume, a lost final receipt, no overwrites and exact
hashes. Keep unsupported pairs on an already-reachable LAN or report Nearby
unavailable. Do not auto-switch networks or silently relay through the internet.

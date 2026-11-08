# DropFlow transfer-engine lab

Experimental branch: `codex/transfer-engine-lab`. No release, default Nearby
mode, OS network changes, or new externally reachable production listener.

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

The two experimental components are **not wired together**. Batch v2 assumes
an authenticated encrypted stream, an approved manifest and an authenticated
resume-point exchange. There is no production TLS pairing, persistent checkpoint
journal, filesystem publication integration, Android batch v2 implementation,
or new UI in this branch. The callbacks in the prototype define those boundaries;
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

Measure current and candidate builds on the same physical pairs, both
directions, with a multi-GB file and a folder with hundreds of small files.
Include interruption, resume, a lost final receipt, no overwrites and exact
hashes. Keep unsupported pairs on an already-reachable LAN or report Nearby
unavailable. Do not auto-switch networks or silently relay through the internet.

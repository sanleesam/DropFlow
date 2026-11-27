# Transfer speed: plan, measurement and results

Goal: get DropFlow close to what the network link can actually carry, without
weakening integrity checks, approval, or verified completion. Nothing here
changes the user's Wi-Fi or adds a relay.

## Phases

| Phase | What | Status |
| --- | --- | --- |
| 0 | Measure: datasets, `[Perf]` logs, device matrix, link baseline | Tooling done; hardware runs pending |
| 1 | Wire-compatible quick wins | Done (below) |
| 2 | DFP/1.1: batch streaming, resume, large manifests, peer notices ([spec](TRANSFER_PROTOCOL.md#7-dfp11-negotiated-features)) | Desktop done and tested end to end on one Mac; Android in progress |
| 3 | Raise the ceiling: direct links (Nearby lab), best-path selection, slow-band hint | Planned; depends on Nearby hardware proof |
| 4 | Promote to Beta once the device matrix passes | See [RELEASE_PLAN.md](RELEASE_PLAN.md) |

## What "faster" means here

Two different limits, and only one of them was the software:

- **Many files.** The original protocol stops after every file header and waits
  for the receiver to answer. On Wi-Fi each wait is a round trip of several
  milliseconds, plus whatever the receiver does before answering. A thousand
  photos paid that a thousand times. DFP/1.1 `batch` removes the wait.
- **One large file.** This was already limited by the link, not by DropFlow.
  It gets no faster on the same Wi-Fi. Only a better link (Phase 3, Ethernet,
  or a faster router) raises it.

There was also a hard stop: the file list had to fit in 64 KB, which is roughly
400 files. Larger selections failed outright. That limit is gone between
DFP/1.1 peers.

## Phase 2 results (one Mac, no radio involved)

Measured 27 November 2026 on an Apple Silicon MacBook Pro, optimized build.
These show what the software no longer costs. They are **not** Wi-Fi results.

| Test | Before | After |
| --- | ---: | ---: |
| 500 files × 48 KiB, link with 2 ms latency each way | 3,334 ms | 123 ms (27×) |
| Same, 5 ms each way | 7,039 ms | 142 ms (49×) |
| Same, no added latency | 165 ms | 108 ms (1.5×) |
| 1,000 files × 200 KiB, loopback, to disk | 210 MiB/s | 930 MiB/s |
| 1 GiB file, loopback, to disk | 501 MiB/s | 2,125 MiB/s |
| SHA-256 on Apple Silicon | 566 MiB/s | 2,991 MiB/s |

The latency rows come from `cargo test --release --lib many_small_files_over_a_slow_link -- --ignored --nocapture`,
which puts a delay between the two real engines. The delay does not limit
bandwidth, so a real link adds the time it needs to carry the bytes to both
columns. Expected on Wi-Fi: the per-file part (several ms × number of files)
disappears; the bytes still take as long as the link needs. For a folder of
photos on a 30 MB/s link that is roughly 4× faster; for thousands of small
files it is more; for one big file it is unchanged.

The large-file and hashing rows come from using the CPU's SHA-256 instructions
on Apple Silicon. That matters on wired and very fast links, and for how long a
resume takes to check what it already has.

End-to-end check through the real desktop app (debug build, isolated profile,
sender `examples/dfp_peer`): 1,000 + 100 files and a 1 GiB file received in
both protocol modes and verified by SHA-256; a 1 GiB send killed part-way kept
419 MiB and the retry sent only the remaining 605 MiB.

Still unmeasured: every pair in the device matrix below, on real hardware.

## Testing the engine without a second device

These exercise the real engine and catch protocol and storage bugs. They say
nothing about Wi-Fi.

- **Engine tests**: `cargo test --locked --lib session_tests` in
  `apps/desktop/src-tauri`. Both protocol generations, every old/new pairing,
  resume, cancellation, hostile input.
- **Command-line peer**: `cargo build --release --example dfp_peer`, then
  `dfp_peer receive <folder> --port N` and `dfp_peer send <host> <port> <paths>`.
  `--legacy` makes it behave like a release from before DFP/1.1. It accepts
  everything, so use it only on a network you control.
- **The real desktop app, isolated**: `pnpm tauri build --debug --no-bundle`,
  then start `target/debug/Dropflow` with `HOME` set to an empty folder. It
  gets its own settings, history and `Downloads/DropFlow`, so your installed
  app and files are untouched. Its log names the port to send to.
- **Android emulator**: install the debug APK and the `InteropInstrumentation`
  test APK (see [ANDROID_MAC_EMULATOR_TEST.md](ANDROID_MAC_EMULATOR_TEST.md)).
  Desktop → Android goes through `adb forward tcp:<local> tcp:42100` with
  `-e decision accept -e peerId dfp-peer`; Android → desktop uses `-e sendDir`.

## Phase 1 changes (no protocol change; old and new builds interoperate)

1. **`TCP_NODELAY` on every transfer socket** (desktop `CancellableStream`,
   Android `TransferConnection`). Each file is a request/response exchange
   (`FileHeader` → `TransferAck`). With Nagle on, a small reply can wait for the
   peer's delayed ACK: roughly 40 ms on Linux/Android and up to 200 ms on
   Windows, potentially twice per file.
2. **Every control frame is one write** (`write_frame` / `FrameUtils.writeFrame`).
   Same bytes on the wire as before; tests check byte equality.
3. **Android keeps the radio and process awake during a transfer.** It holds a
   Wi-Fi high-performance and low-latency lock, plus a bounded partial wake lock.
   It also runs a `dataSync` foreground service with a "Transferring files"
   notification. All of this is tied to the single transfer slot, so it is
   released when the transfer ends or fails. Android 12+ doesn't allow starting
   the service from the background (for example, an incoming transfer while the
   app is hidden); the locks still apply then.
4. **`[Perf]` timing line at the end of every successful transfer**, on both
   platforms, in the same format. Timing starts after approval, so a person's
   decision time is never counted.

Not done, on purpose: a sender read-ahead thread and a receiver
write-behind thread. At Wi-Fi rates, the OS socket buffer already overlaps disk
and hashing with the network. Reading and hashing 256 KiB takes well under 1 ms,
while sending it at 50 MB/s takes about 5 ms. Add these only if Phase 0 shows the
link idling, most likely on Android receive (MediaStore/FUSE) or wired gigabit.

Loopback check (macOS, `cargo test --release --lib per_file_exchange -- --ignored --nocapture`):
200 per-file exchanges took 15.6 ms with the old split writes and 3.8 ms with
single-write frames and `TCP_NODELAY`. Loopback doesn't reproduce the delayed-ACK
stall, which needs a real network stack. The real effect must come from the
hardware runs below.

## Phase 0: how to measure

### Device matrix

Run every pair **in both directions**, on the same Wi-Fi network, devices
close to the router, nothing else heavy running:

| Pair | Why |
| --- | --- |
| Windows ↔ Windows | PC to PC; Windows delayed ACK is the longest |
| Mac ↔ Mac | PC to PC on Apple's stack |
| Mac ↔ Windows | Mixed desktop |
| Windows ↔ Android | Main phone pair |
| Mac ↔ Android | Main phone pair |
| Android ↔ Android | Optional |

If one desktop is on Ethernet, record it: only one Wi-Fi hop, so expect more.

### Datasets

```sh
python3 scripts/transfer-bench.py make ~/DropFlowBench          # 4 GiB + 1,000×200 KiB + 100×20 MiB
python3 scripts/transfer-bench.py make ~/DropFlowBench --large-gib 1   # lighter
```

Copy the folder to each sender (Android: put it in Downloads). Send `large`,
`photos` and `medium` as three separate transfers. Then verify every file on
the receiver:

```sh
python3 scripts/transfer-bench.py verify ~/DropFlowBench/manifest.json "<receive folder>"
```

A run only counts if verification says `all files match`, and the sender showed
completion only after the receiver confirmed it.

### Link ceiling

Measure what the link itself can do on the same pair, same place, using
`iperf3` (a network test tool you install on both devices):

```sh
iperf3 -s                                # on the receiver
iperf3 -c <receiver-ip> -t 20            # on the sender; repeat with -R for the other direction
```

Score = DropFlow MiB/s ÷ iperf3 MiB/s. Target for `large`: at least 80%.

### Collecting numbers

- Desktop log: macOS `~/Library/Application Support/com.dropflow.desktop/dropflow.log`,
  Windows `%APPDATA%\com.dropflow.desktop\dropflow.log`.
- Android: `adb logcat -s DropFlowFileSender DropFlowFileReceiver > android.log`.

```sh
python3 scripts/transfer-bench.py perf dropflow.log android.log
```

Run each case 3 times with the **previous release** (`0.1.2-beta.21`, Android
`0.2.0-alpha.5`) and with the new build, and keep the median. To compare on
the same build, turn **Settings → Transfers → Faster transfers** off on either
desktop: that forces the original exchange. Record the Wi-Fi band (2.4/5/6 GHz) and link rate shown by the OS.

### Results

| Pair (sender → receiver) | Dataset | Build | MiB/s | ms/file | iperf3 MiB/s | Score |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| _pending hardware runs_ | | | | | | |

## Ceilings to expect

Through a router, phone ↔ laptop traffic crosses the air twice, so it tops out
near half the link rate. Rough single-stream TCP figures for good conditions:
about 25–40 MB/s on Wi-Fi 5 phone ↔ laptop, more on Wi-Fi 6/6E, and up to
roughly 110 MB/s when the desktop end is on gigabit Ethernet. Phase 1 removes
software stalls; only Phase 3 (a direct link or a wired end) raises the
ceiling itself. These are expectations, not measurements.

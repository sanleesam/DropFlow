# Transfer speed: plan, measurement and results

Branch `perf/transfer-quick-wins`. Goal: get DropFlow close to what the network
link can actually carry, without weakening integrity checks, approval, or
verified completion. Nothing here changes the user's Wi-Fi or adds a relay.

## Phases

| Phase | What | Status |
| --- | --- | --- |
| 0 | Measure: datasets, `[Perf]` logs, device matrix, link baseline | Tooling done; hardware runs pending |
| 1 | Wire-compatible quick wins | Done on this branch (below) |
| 2 | Negotiated protocol v2: batch stream, parallel streams for big files, optional compression, Android resume | Planned |
| 3 | Raise the ceiling: direct links (Nearby lab), best-path selection, slow-band hint | Planned; depends on Nearby hardware proof |
| 4 | Roll out behind a Development toggle, then Beta | Planned |

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

Not done in Phase 1, on purpose: a sender read-ahead thread and a receiver
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

Run each case 3 times with the **current release** and with this branch, and
keep the median. Record the Wi-Fi band (2.4/5/6 GHz) and link rate shown by the OS.

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

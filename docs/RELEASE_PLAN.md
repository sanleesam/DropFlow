# Versioning and the road to a stable release

Written 27 November 2026. The rules for version numbers are in
[VERSIONING.md](VERSIONING.md) and the mechanics of cutting a release are in
[RELEASE.md](RELEASE.md). This page says which versions come next, what each
must prove, and when a stable 1.0 is realistic.

## How versions move

| Part | Meaning | Example |
| --- | --- | --- |
| `0.MINOR.0` | A set of new features. While the major number is 0, a MINOR may change behaviour. | redesign + new engine = `0.2.0` |
| `0.MINOR.PATCH` | Fixes only. | `0.2.1` |
| `-dev.N` | Development channel. Anything on a feature branch that passes the automated checks. For testing on your own devices. | `0.2.0-dev.1` |
| `-alpha.N` | Android only for now: features still missing. | `0.3.0-alpha.1` |
| `-beta.N` | Feature-complete for that MINOR and tested on real hardware. The desktop counter never restarts (old updaters compare only that number). | `0.2.0-beta.22` |
| `-rc.N` | Release candidate. Only fixes after this. | `1.0.0-rc.1` |
| none | Stable. | `1.0.0` |

Rules that keep this honest:

1. **A build is promoted, never rebuilt.** dev → beta → rc → stable is the same
   code moving up after it passes the next gate.
2. **Passing tests is not passing hardware.** A version reaches Beta only when
   the device matrix in [TRANSFER_SPEED.md](TRANSFER_SPEED.md) has been run on
   physical devices and the results are written down.
3. **The protocol has its own version.** App versions say nothing about wire
   compatibility. Today: DFP/1, plus the negotiated DFP/1.1 features. Any two
   releases must still be able to exchange files unless a MINOR says otherwise
   in its notes.
4. **Desktop and Android keep separate numbers until 1.0**, then share
   `MAJOR.MINOR` (patch numbers stay independent).

## Where things are

| | Published | In source now |
| --- | --- | --- |
| Desktop Beta | `0.1.2-beta.21` | target `0.2.0-beta.22` |
| Desktop Development | `0.1.2-dev.4` | this branch |
| Android Alpha | `0.2.0-alpha.5` | this branch |
| Protocol | DFP/1 | DFP/1 + DFP/1.1 (`batch`, `resume`) |

## Milestones

### 0.2: redesign and the faster engine (now)

Desktop `0.2.0`, Android `0.3.0-alpha`.

- Liquid Glass interface on both platforms.
- DFP/1.1: files stream without a pause between them, interrupted transfers
  continue, no limit of about 400 files per transfer, each side tells the
  other why it stopped.

Gate to Beta (`0.2.0-beta.22`, Android `0.3.0-beta.1`):

- [ ] Device matrix run in both directions on real hardware: Mac ↔ Windows,
      Mac ↔ Android, Windows ↔ Android, and one PC ↔ PC pair.
- [ ] New ↔ old interop on real devices: a `0.2` build exchanging files with
      `0.1.2-beta.21` and with Android `0.2.0-alpha.5`, both directions.
- [ ] Resume after a real Wi-Fi drop (turn Wi-Fi off mid-transfer, send again).
- [ ] 1,000-photo folder and a file over 4 GB, verified by hash.
- [ ] Android receives while the app is in the background.

### 0.3: trust

A stable file-transfer app cannot send files in the clear or accept a device
name on faith. Today the connection is not encrypted and a device's name and
ID are not verified, which is why every transfer still asks.

- Encrypted connection between devices (TLS).
- Pairing: a short code shown on both devices the first time, then the
  device is recognised by its key, not its name.
- "Remember this device" can then safely skip the prompt.
- Android: resume on the receiving side.

Gate: an independent read of the pairing and encryption design; old builds
still interoperate (unencrypted, clearly labelled) for one MINOR.

### 0.4: polish for release candidates

- Signed and notarised macOS build; signed Windows installer (without these,
  macOS and Windows warn users away from the app).
- A Stable update channel next to Beta and Development.
- Accessibility pass (keyboard, VoiceOver/Narrator/TalkBack, contrast).
- Play Store or a documented direct-install path for Android.
- Direct device-to-device link only if the hardware probes pass; it does not
  block 1.0.

### 1.0: stable

`1.0.0-rc.1`, at least two weeks of daily use with no data-loss or
stuck-transfer bug, then `1.0.0` on the Stable channel for desktop and
Android together.

## When

These are estimates, and each one waits on its gate, not on the calendar. The
slowest part is hardware testing, which only you can do.

| Version | Earliest | Depends on |
| --- | --- | --- |
| `0.2.0-dev.N` | now | automated checks |
| `0.2.0-beta.22` | mid-December 2026 | the 0.2 gate above |
| `0.3.0-beta` | late January 2027 | encryption and pairing built and reviewed |
| `1.0.0-rc.1` | late February 2027 | signing certificates, Stable channel |
| **`1.0.0`** | **mid-March 2027** | two clean weeks on the release candidate |

What would move the date: skipping encryption brings 1.0 forward by about six
weeks but ships a stable app that sends files unencrypted, which I do not
recommend. Signing needs paid developer accounts (Apple, and a Windows
certificate); ordering them early removes the most likely late slip.

## Day-to-day

- Work happens on a feature branch. Each coherent, tested step gets a
  `-dev.N` build for your devices.
- When a milestone's gate is fully ticked, the branch merges to `main` and the
  same commit is tagged `-beta.N`.
- Fixes to a published Beta are PATCH releases from `main`.
- Android follows the same steps with `android-v…` tags.

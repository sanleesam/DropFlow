# DropFlow

> Fast, local file transfers between your devices.

DropFlow is a local-first, peer-to-peer file transfer application designed to make moving files between devices simple and reliable.

The project began as a way to seamlessly transfer files between a MacBook and an Android phone, but has since evolved into a cross-platform application with support for macOS, Windows and Android.

Everything happens directly over your local network.

No cloud.

No accounts.

No unnecessary setup.

---

## Current Status

🚧 Desktop Beta · Android Alpha development

Desktop and Android have independent Semantic Versions and maturity stages.
Published versions are never renamed. See [the versioning policy](docs/VERSIONING.md).
Current releases:

- **Desktop**: [`0.1.1-beta.19`](https://github.com/sanleesam/DropFlow/releases/tag/v0.1.1-beta.19) (Windows x64, macOS arm64)
- **Android**: published legacy `0.1.0-beta.1`; the next, **unreleased** source target
  is `0.2.0-alpha.1` (install number 18). See [current Android readiness](docs/ANDROID_ALPHA1.md).

See [CHANGELOG.md](CHANGELOG.md), [docs/RELEASE.md](docs/RELEASE.md) and [docs/TEST_PLAN.md](docs/TEST_PLAN.md).

### Releases & tags

Desktop and Android release pipelines are fully independent and are triggered
by different tag prefixes — never tag both platforms with the same ref:

| Platform | Tag example | Workflow | Artifact |
| --- | --- | --- | --- |
| Desktop Beta | `v0.1.1-beta.19` | `.github/workflows/release-beta.yml` | Updater-signed bundles + `latest.json` |
| Android | `android-v0.2.0-alpha.1` | `.github/workflows/release-android.yml` | Same-key signed APK + Android-only updater manifest |

### Completed

- ✅ Device discovery (mDNS)
- ✅ Custom TCP transfer engine (DFP/1)
- ✅ Desktop persistent history/state; Android session history and saved preferences
- ✅ Native desktop interface
- ✅ Smooth view transitions
- ✅ Desktop resume support (Android SAF resume is not implemented)
- ✅ Sleep prevention during active transfers
- ✅ Android app with discovery, send and receive
- ✅ Desktop built-in updater; Android verified update flow implemented, publication/upgrade acceptance pending

### Troubleshooting discovery

Discovery uses mDNS on UDP port 5353. If a device never appears:

1. Make sure DropFlow is running on every device and all devices are on the
   same Wi-Fi network (guest networks and AP isolation block multicast).
2. Allow DropFlow through the OS firewall — macOS: System Settings → Network →
   Firewall; Windows: allow the app on private networks when prompted.
3. Desktop writes a detailed discovery log to `<app data>/logs/dropflow.log`.

### Planned

- ⏳ Reliability & stress testing
- ⏳ Desktop packaging polish
- ⏳ Notifications on Android

---

## Platforms

### Desktop

- ✅ macOS
- ✅ Windows

### Mobile

- 🚧 Android (Alpha development; older public APK used a Beta label)

---

## Why DropFlow?

There are already plenty of file-sharing applications, but most either rely on cloud services, require user accounts or include features that aren't necessary for local transfers.

DropFlow focuses on doing one thing well:

> Fast, reliable file transfers between your own devices.

The project is built around a few simple principles:

- Local-first
- Peer-to-peer communication
- Fast transfers
- Reliable transfers
- Native desktop experience
- Simple user experience

---

## Technology Stack

### Desktop

- Tauri 2
- Rust
- React
- TypeScript
- Tailwind CSS

### Mobile

- Kotlin
- Jetpack Compose

### Networking

- Local Wi-Fi
- mDNS device discovery
- Custom TCP transfer protocol
- JSON control protocol

---

## Repository Structure

```text
DropFlow/
├── apps/
├── docs/
├── README.md
├── AGENTS.md
└── TASKS.md
```

---

## Development Workflow

Every feature follows the same workflow before becoming part of the project.

```text
Planning
    ↓
Architecture Review
    ↓
Approval
    ↓
Implementation
    ↓
Testing
    ↓
Code Review
    ↓
Merge
```

Nothing is implemented directly on the `main` branch.

---

## Documentation

Major systems are designed before implementation.

Architecture documents are stored inside the `docs/` directory and are updated as the project evolves.

---

## Roadmap

Current priorities are:

- Complete the built-in updater
- Finish the beta release pipeline
- Reliability & stress testing
- Desktop packaging
- Android development
- Public v1.0 release

---

## Project Goal

Build a fast, reliable and maintainable peer-to-peer file transfer application that provides a seamless experience across macOS, Windows and Android while keeping every transfer completely local.

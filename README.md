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

🚧 Active Development

The desktop application is currently approaching its first beta release.

### Completed

- ✅ Device discovery (mDNS)
- ✅ Custom TCP transfer engine
- ✅ Transfer history
- ✅ Persistent application state
- ✅ Native desktop interface
- ✅ Smooth view transitions
- ✅ Resumable file transfers
- ✅ Sleep prevention during active transfers

### Currently Working On

- 🔄 Built-in updater
- 🔄 Beta release pipeline

### Planned

- ⏳ Reliability & stress testing
- ⏳ Desktop packaging
- ⏳ Android application

---

## Platforms

### Desktop

- ✅ macOS
- ✅ Windows

### Mobile

- 🚧 Android (In Development)

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

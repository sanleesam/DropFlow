# DropFlow Project Structure

## Root

DropFlow/
│
├── apps/
├── shared/
├── docs/
│
├── README.md
├── ROADMAP.md
├── SPECIFICATION.md
├── ARCHITECTURE.md
├── PROJECT_STRUCTURE.md
├── AI_WORKFLOW.md
└── TASKS.md

---

## apps/

Contains every application.

apps/
│
├── desktop/
└── android/

---

## apps/desktop/

Desktop application (macOS).

Technology:

- Tauri 2
- React
- TypeScript
- Rust

---

## apps/android/

Android application.

Technology:

- Kotlin
- Jetpack Compose

---

## shared/

Code shared between applications.

shared/
│
├── protocol/
└── types/

protocol/
Shared JSON message definitions.

types/
Shared interfaces and data structures.

---

## docs/

Future documentation.

Examples:

- Networking
- Protocol
- UI decisions
- Developer notes
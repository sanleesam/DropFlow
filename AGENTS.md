# AGENTS.md

## Project principles

DropFlow is a local-first file transfer application with a Tauri desktop app
and a native Android app. Prefer simplicity, reliability, maintainability,
and small, focused changes over clever or complex solutions.

## Actual codebase

- `apps/desktop/`: React, TypeScript, Vite, pnpm, and Tauri 2.
  There is no root JavaScript package; run desktop commands in this directory.
- `apps/desktop/src/App.tsx`: `SettingsProvider` → `ToastProvider` →
  `DropFlowProvider` → `AppShell`.
- `apps/desktop/src/components/shell/`: desktop navigation and window layout.
- `apps/desktop/src/pages/`: Home, Devices, Transfers, Files, and Settings.
- `apps/desktop/src/components/ui/`: reusable desktop UI primitives.
- `apps/desktop/src/App.css` and `src/ui/desktop.css`: base styles and the
  desktop design system respectively. Prefer shared tokens over page-specific
  hard-coded styling.
- `apps/desktop/src/session/DropFlowProvider.tsx`: discovery, selected peers,
  file selection/drop handling, transfers, incoming requests, and history.
  Reuse this provider rather than duplicating session logic in pages.
- Desktop settings, toasts, and updater logic have existing providers,
  hooks, services, and types. Preserve their public contracts.
- `apps/desktop/src-tauri/`: Rust backend and native application configuration.
  Backend modules handle discovery, transfers, filesystem browsing, state,
  updates, logging, and power management. `window_material.rs` handles native
  window presentation, not file-transfer behavior.
- `apps/android/`: Kotlin/Jetpack Compose application with Gradle, ViewModels,
  DataStore preferences, and network discovery/transfer code under
  `app/src/main/java/com/dropflow/android/core/`. `DropFlowApplication` owns the shared
  transfer engine.
- `protocol/PROTOCOL.md`: transfer protocol documentation.
- `docs/`: architecture, specification, project structure, UI, testing, and
  release documentation. Some planning documents may lag behind the code;
  inspect the implementation before treating a described feature as present.
- `.github/workflows/`: separate desktop Beta, desktop Development, and
  Android release workflows. Inspect the relevant workflow before release work.

## Approval and branch workflow

1. Read only relevant documentation and source files. Inspect Git status,
   the current branch, and any existing changes first.
2. Explain the implementation plan and obtain explicit approval before edits.
   A direct instruction to proceed with a stated implementation is approval;
   do not repeatedly ask for approval of the same scope.
3. Continue an existing, user-designated feature branch for related approved
   work. Do not create a new branch merely because a task continues.
4. If a new branch is needed, propose `feature/<feature-name>` and create it
   only after approval. Keep unrelated features on separate branches.

During planning, only inspect files, run read-only Git commands, and ask
questions. Do not create/edit files, install packages, scaffold, switch
branches, or commit before implementation approval.

Explicit task instructions can override the default branch or verification
workflow. Identify material conflicts and explain the resolution. Preserve
all unrelated working-tree changes; never reset or overwrite user work.

## Implementation boundaries

- Modify only files needed for the approved task.
- Do not refactor unrelated code, rename project structure, or change
  architecture without explicit approval.
- For UI work, preserve backend logic, Tauri invokes/events, providers,
  TypeScript types, protocol behavior, settings persistence, and real data.
- Do not add decorative controls that imply unavailable functionality.
  Preserve the real Beta/Development update channels; do not invent channels
  or claim scheduled update features exist without checking implementation.
- Native presentation changes must preserve solid-background fallbacks,
  readable contrast, keyboard focus, and reduced-motion support.
- Preserve existing icon artwork and alpha transparency. Do not regenerate
  or redesign assets as an incidental step in an unrelated UI task.
- Keep commits focused and incremental when requested. Inspect the staged
  diff before committing; include no unrelated changes.
- Never change live network configuration or system accessibility preferences
  to make a diagnostic pass. Use isolated fixtures and report validation limits.
- Do not push, merge, publish, tag, or trigger releases without explicit
  authorization. A successful local build is not release authorization.

## Commands

Run these from `apps/desktop/`:

```sh
pnpm dev                 # Browser-only frontend preview
pnpm tauri dev           # Native desktop development
pnpm tsc --noEmit        # Type check
pnpm build               # Frontend type check and production build
pnpm test:ui             # Existing UI/lifecycle regression checks
pnpm tauri build         # Native bundle build; inspect release config first
```

For local native visual validation without updater signing or release bundles:

```sh
pnpm tauri build --debug --bundles app --config '{"bundle":{"createUpdaterArtifacts":false}}'
```

Run Android commands from `apps/android/`, for example `./gradlew assembleDebug`.
Run Rust checks from `apps/desktop/src-tauri/` when backend changes require them.
Do not run Android or broad backend checks for frontend-only work unless they
are necessary or explicitly requested. Respect the task's verification scope.

## Documentation and completion

Update relevant documentation when requested or when approved work changes
behavior, architecture, or development conventions. Do not rewrite unrelated
documents or document planned features as implemented. Use actual repository
paths; `docs/PROJECT_STRUCTURE.md` is not a root-level file.

Before handing off:

- Run the checks relevant to the changed code and report their results.
- For native visual work, validate in the actual application, not only a
  browser preview. Check applicable pages, focus states, and material fallbacks.
- Inspect the final diff and Git status for unrelated changes.
- Report blocked or untested cases honestly. Build/unit-test success does not
  establish physical Android↔desktop interoperability or platform visual parity.
- Leave the approved work ready for review, without pushing or merging it.

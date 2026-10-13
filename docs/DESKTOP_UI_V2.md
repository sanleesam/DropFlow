# Desktop UI v2

This is a frontend-only redesign on `feature/desktop-ui-v2`. Rust, DFP/1,
discovery, Tauri configuration, providers, updater service, endpoints and release
workflows are unchanged. The backend already defaults to dark mode; existing
saved light/dark preferences are respected.

## Design decisions and research

- [Apple materials](https://developer.apple.com/design/human-interface-guidelines/materials)
  and [sidebars](https://developer.apple.com/design/human-interface-guidelines/sidebars)
  informed the separation of navigation, content and contextual information.
- [Microsoft Mica](https://learn.microsoft.com/en-us/windows/apps/design/style/mica)
  and [Acrylic](https://learn.microsoft.com/en-us/windows/apps/design/style/acrylic)
  informed restrained material use, layered surfaces and opaque fallbacks.
  DropFlow uses CSS interpretation, not native Mica/Acrylic integration.
- [Raycast's technical design discussion](https://www.raycast.com/blog/a-technical-deep-dive-into-the-new-raycast)
  informed native system typography, default desktop cursors, short transitions
  and avoidance of gratuitous motion. Native window behavior remains unchanged.
- [LocalSend](https://github.com/localsend/localsend) was consulted as a production
  local-transfer application: discovery and sending remain the main workflow.

The reference images establish direction, not literal UI requirements. There are
no imitation traffic lights, decorative stock photos or fabricated network/OS data.

## Significant changes

- `ui/desktop.css` defines navy/light surfaces, readable text, spacing, radii,
  borders, shadows and interaction tokens. Primary content is opaque. Only the
  sidebar gets 16px backdrop blur at 84–86% opacity, with a solid fallback.
- Shared React primitives provide page headers, selectable device cards,
  original vector device visuals, progress bars, empty states, setting rows,
  switches and focus-contained dialogs.
- Home makes nearby-device selection, native file/folder picking, the real file
  queue, sending and recent activity the primary flow. Unsupported ZIP/custom
  naming choices were removed; their previous UI state never affected sending.
- Devices separates actual discovery feedback from local-device information.
  Search, refresh, rename, visibility, confirmation, trust and file access retain
  their existing handlers. “Add Device” is now honestly labelled “Refresh”, and
  hiding a peer is labelled “Hide from this view”, not “Disconnect”.
- Transfers retains filtering, search, cancellation, history and open/reveal
  actions. History details can be selected while a live transfer exists. Pause
  placeholders were removed; the old Home “Pause” actually cancelled. Progress
  is accessible and speed charts draw only measured samples.
- Settings is organized into General, Transfers, Devices, Updates and Appearance.
  Updates uses the existing `useUpdater` hook and actual release information.
  It retains Beta/Development selection, check, download and restart actions;
  unsupported scheduling controls were not added. No update is installed by
  visiting Settings. Release notes are shown as safe text.
- Files retains local browsing, exchanged files, previews, folder creation,
  rename, deletion, copy, sending and open/reveal. Its presentation shares the
  same surfaces and keyboard-accessible file-selection controls. Loading and
  unreadable-folder states are distinct from an empty folder, with retry guidance.
- Toasts have visible dismissal and semantic status/error roles. Incoming and
  clear-history dialogs contain keyboard focus, restore focus and support Escape.
  Incoming response failures now produce a notification without changing the
  underlying response command or closing behavior.
- Runtime Google Fonts loading was removed in favor of macOS/Windows system
  typography. Motion respects both system preferences and the existing setting.

## Assets and licenses

No new runtime dependencies, downloaded illustrations or hotlinked images were
introduced. Device illustrations are original inline SVGs, not copied from Apple,
Raycast or reference artwork. The existing DropFlow brand mark is retained.

[Lucide](https://lucide.dev/license) is the existing icon dependency. It is ISC
licensed; Feather-derived icons carry MIT terms. The complete relevant notices
are bundled in `public/third-party-licenses.txt`. Existing project assets remain
untouched. Prettier was used temporarily for formatting, not added as a dependency.

## Responsive and accessibility behavior

The native window remains 1280 × 820, minimum 1100 × 720. Context panels move
below the content at 1120px; device details become a compact split panel. The
file browser repositions its details panel at 1250px. Additional CSS handles
narrower preview sizes without changing native constraints. Focus rings, named
switches, radio groups, keyboard-operated settings tabs and progress semantics
are shared across pages. `prefers-reduced-transparency` uses opaque navigation.

## Verification

From `apps/desktop`:

```sh
pnpm tsc --noEmit
pnpm build
pnpm test:ui
```

`test:ui` runs the existing transfer lifecycle suite (18 scenarios in the source,
although its old console heading says 10) and 12 UI primitive assertions using
Vite's existing toolchain. Generated test bundles are temporary and removed.

Native `pnpm tauri dev` compiled and launched without backend changes. Browser
visual review covers actual rendered pages, empty/error states, dark/light
appearance and supported desktop sizes. Browser preview cannot perform native
discovery or file dialogs. Native screenshot inspection requires macOS computer-use
permissions. Physical macOS/Windows/Android transfers and actual update installation
still require device testing before release.

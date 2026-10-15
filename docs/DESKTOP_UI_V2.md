# Desktop UI v2

This redesign is on `feature/desktop-ui-v2`. DFP/1, discovery, providers,
updater service, endpoints and release workflows are unchanged. The subsequent
native-glass extension changes only window initialization/configuration and CSS;
see [native materials](NATIVE_WINDOW_MATERIALS.md). The backend already defaults
to dark mode; existing saved light/dark preferences are respected.
The subsequent GSAP/glass-edge pass is frontend-only and preserves that native
material integration and all application/provider contracts.

## Design decisions and research

- [Apple materials](https://developer.apple.com/design/human-interface-guidelines/materials)
  and [sidebars](https://developer.apple.com/design/human-interface-guidelines/sidebars)
  informed the separation of navigation, content and contextual information.
- [Microsoft Mica](https://learn.microsoft.com/en-us/windows/apps/design/style/mica)
  and [Acrylic](https://learn.microsoft.com/en-us/windows/apps/design/style/acrylic)
  informed restrained material use, layered surfaces and opaque fallbacks.
  The native extension now uses macOS vibrancy and Windows Mica, with controlled
  CSS overlays and solid fallbacks. Acrylic was evaluated but not enabled.
- [Raycast's technical design discussion](https://www.raycast.com/blog/a-technical-deep-dive-into-the-new-raycast)
  informed native system typography, default desktop cursors, short transitions
  and avoidance of gratuitous motion. Native window behavior remains unchanged.
- [LocalSend](https://github.com/localsend/localsend) was consulted as a production
  local-transfer application: discovery and sending remain the main workflow.

The reference images establish direction, not literal UI requirements. There are
no imitation traffic lights, decorative stock photos or fabricated network/OS data.

## Significant changes

- `ui/desktop.css` defines titanium/light surfaces, readable text, spacing, radii,
  borders, shadows and interaction tokens. Confirmed native materials expose
  controlled translucency across the workspace, cards, and detail panels;
  controls and floating surfaces retain stronger fills for readability.
  Native material is exposed only after successful initialization; the previous
  CSS sidebar blur is removed to avoid double compositing.
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

## Discovery pulse and reflective edges (2026-10-16)

- Searching uses a decorative signal beacon, two slowly expanding rings and a
  softly breathing edge instead of a rotating spinner and dashed frame. The
  4.4-second GSAP timeline changes only transform/opacity, not layout or text.
  Empty/error states do not animate or imply active discovery; their real retry
  action and status announcements are retained.
- Card, device, drop-zone, button, tab, channel, dialog and toast rims share a
  thin masked reflection gradient. Highlights vary around the edge rather than
  drawing a uniform heavy outline. Straight dividers fade toward their ends.
  Selected update channels use one icy-blue edge instead of a doubled outline.
  Text fields retain stronger boundaries and all focus rings remain visible.
- The drop area is now a glass-framed surface rather than a dashed box. Native
  drag-over feedback still changes its real text and edge treatment; file/folder
  picking and drag/drop handlers are unchanged.
- `ui/motion.ts` scopes and reverts GSAP effects through
  [matchMedia](https://gsap.com/docs/v3/GSAP/gsap.matchMedia%28%29/), including live
  system reduced-motion changes. Existing `settings.reduceAnimations` disables
  both pulse and page motion without changing the provider. Timelines pause
  while the document is hidden; listeners and styles revert on cleanup.
- Page headers and surface groups settle by 5px over 320ms, with a short 35ms
  stagger. Content is never hidden or faded out, and navigation/focus is not
  delayed. Page motion starts in a layout effect to avoid a first-paint jump.
- Native blur, surface opacities and fallback fills are unchanged. No extra
  backdrop blur or wallpaper image is introduced. Reflective masks disappear
  for reduced transparency/increased contrast, preserving solid boundaries.

## Assets and licenses

The original redesign used existing frontend runtime dependencies. The approved
motion pass adds GSAP 3.15.0, bundled locally with no CDN requests or optional
plugins. Its copyright/license reference is included in
`public/third-party-licenses.txt`; see the published
[GSAP license](https://gsap.com/standard-license).
No downloaded illustrations or hotlinked images were introduced.
The native extension directly references two crates already in
Tauri's dependency graph, without version upgrades. Device illustrations are
original inline SVGs, not copied from Apple,
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
are shared across pages. `prefers-reduced-transparency` and increased contrast
restore opaque workspace, navigation, cards, controls and floating surfaces.

## Verification

From `apps/desktop`:

```sh
pnpm tsc --noEmit
pnpm build
pnpm test:ui
```

`test:ui` runs the existing transfer lifecycle suite (18 scenarios in the source,
although its old console heading says 10) and 18 UI primitive assertions using
Vite's existing toolchain. Generated test bundles are temporary and removed.

Native `pnpm tauri dev` compiled and launched without transfer/backend changes. Browser
visual review covers actual rendered pages, empty/error states, dark/light
appearance and supported desktop sizes. Browser preview cannot perform native
discovery or file dialogs. The native-glass extension was compiled, bundled and
inspected on macOS; its exact verification limits are recorded in the native
materials document. Physical macOS/Windows/Android transfers and actual update
installation still require device testing before release.

### GSAP/glass-edge pass verification (2026-10-16)

- Type checking, frontend production build, the existing lifecycle suite, and all
  18 UI primitive assertions passed. A production-assets debug `.app` was rebuilt
  at the existing local bundle path with updater artifacts disabled by CLI override.
- Native Home, Devices, Transfers, Settings/Updates and the clear-history dialog
  were inspected. The real empty discovery card transitioned to the active beacon
  on retry. The dialog focused Cancel and dismissed with Escape; history remained
  at six entries. The saved dark theme and Development update channel were retained.
- Native Web Inspector confirmed `vibrancy`, solid (not dashed) frames, and
  `mask-composite: exclude`. Two read-only samples showed ring scale/opacity and
  edge opacity advancing over time. No diagnostic UI/code was added to the bundle.
- With explicit user permission, Reduce animations was temporarily enabled. In
  the active search state, both rings computed to `display: none`, their inline
  transform/opacity styles were absent, the pulse edge had no inline animation
  style, and page transforms were cleared. The setting was restored to Off and
  verified in the UI. No OS accessibility preference was changed.
- Current captures are local test artifacts under
  `/tmp/dropflow-pulse-glass-evidence/`. As in the previous pass, some isolated
  captures retained stale tab layers; native zoom/restore produced a correct
  settled Settings capture. Isolated captures do not establish live backdrop
  color diffusion or frame-rate/battery performance.
- Files receives the shared visual tokens but was not reopened because of its
  previously identified filesystem hang. Windows, physical transfers, OS
  reduced-motion preference changes, and formal performance/accessibility audits
  were not tested. No backend/provider/protocol/native configuration changed.
- No extra installed app copy, push, merge, tag or release was created. Safari
  was not accessed. Web Inspector was used only for local read-only diagnostics.

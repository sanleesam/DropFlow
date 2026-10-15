# Native window materials

Focused extension of UI v2. Native integration adds a blank React drag region;
the full-window follow-up changes shared CSS and the Appearance description.
No provider, transfer, discovery, protocol, updater, application command,
event, icon or release behavior is changed.

## Implementation and research

- macOS uses one `NSVisualEffectView` with `Sidebar`, `BehindWindow` blending,
  and `Active` state. Translucent workspace, card and detail-panel fills expose
  this same native view across every page. This is not a separate
  native effect for each HTML panel. Active keeps diffusion available when the
  app loses focus; native controls still show the system's inactive appearance.
- Windows uses Mica on Windows 11 and solid CSS when the library reports an
  unsupported platform. Mica samples wallpaper; it does not blur live windows.
  Acrylic was evaluated as the live-blur option, but its documented drag/resize
  regressions on Windows 10 and early Windows 11 make it unsuitable as an
  untested default. Do not describe Windows as see-through glass.
- Installed Tauri 2.11.5 exposes `set_effects`, but its source discards the result
  of the native application inside a main-thread closure. Direct
  `window-vibrancy = 0.6.0` is used to receive supported-platform/native errors.
  No `windowEffects` configuration or second effect system is also enabled.
- `objc2-app-kit = 0.3.2` reads the macOS Reduce Transparency preference. Both
  crates were already transitively present; the lockfile adds only direct edges.
  There are no frontend dependencies or crate version upgrades.
- Current online Tauri docs include newer Liquid Glass options that the installed
  enum does not expose. Apple's `NSGlassEffectView` is a macOS 26+ navigation and
  control material, not a reason to replace the entire content surface. No Tauri
  upgrade or custom Liquid Glass bridge was introduced for this focused feature.

Sources:

- [Tauri configuration](https://v2.tauri.app/reference/config/)
- [Tauri window API](https://v2.tauri.app/reference/javascript/api/namespacewindow/)
- [window-vibrancy support/performance](https://github.com/tauri-apps/window-vibrancy)
- [Apple sidebar material](https://developer.apple.com/documentation/appkit/nsvisualeffectview/material-swift.enum/sidebar)
- [Apple active effect state](https://developer.apple.com/documentation/appkit/nsvisualeffectview/state-swift.enum/active)
- [Apple visual effect views](https://developer.apple.com/documentation/appkit/nsvisualeffectview)
- [Apple Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass)
- [Microsoft Acrylic](https://learn.microsoft.com/en-us/windows/apps/design/style/acrylic)
- [Microsoft Mica](https://learn.microsoft.com/en-us/windows/apps/design/style/mica)

## Surface integration and fallback

The native window is transparent; `tauri.conf.json` enables `macOSPrivateApi`,
and Cargo enables `macos-private-api`. The flag is macOS-only in effect but is
kept in shared config so Tauri's Cargo feature/allowlist validation agrees on
every build host; no per-host manifest rewrite is needed. macOS uses the supported
`Overlay` titlebar with `hiddenTitle: true`, removing the separate gray strip.
Native traffic lights, decorations, shadow, size constraints and file-drop
configuration remain enabled. A blank 36px `data-tauri-drag-region` is visible
only on macOS; native platform state provides matching content insets. The core
start-dragging permission enables Tauri's existing drag handler. No imitation
controls or custom pointer handlers are added. Windows/Linux retain their chrome.

CSS stays opaque in browser previews and before native initialization. Native
code publishes `data-native-material` only after application returns success,
including after a page reload. Supported material states make html/body/root/shell
transparent. The full-window pass uses a 62% dark titanium sidebar, 68% workspace,
40% card fills and 44% detail-panel fills. Nested inset/drop surfaces use 24%
fills; inputs and ordinary buttons use 68% fills. These percentages describe
individual layers, not final composed window opacity: a card also sits over the
workspace tint. Floating dialogs, popovers and toasts use independent 96% fills,
so they remain readable without relying on a workspace layer underneath.
Light mode uses a 78% workspace, 52% cards, 58% detail panels, 80% controls and
97% floating surfaces; its sidebar retains the 94% overlay.

Shared reflection tokens, restrained inset highlights and soft shadows distinguish
cards, controls and panels on every page. Selected devices retain a single icy-blue
edge. The drop zone has a faint inner blue light and a reflective glass rim; primary
buttons retain white-text contrast. Selected switches are blue in both themes,
correcting a more-specific legacy dark style that made an enabled switch gray.
Secondary text is brighter on the exposed workspace, but quieter within tinted
cards to preserve hierarchy. Light-mode secondary colors are darkened for the
new translucent surfaces. There is no CSS backdrop blur or image-based fake glass.
Worst-case flat white behind the
dark sidebar gives 4.59:1 for subtle text; flat black behind the light sidebar
gives 4.61:1. Native blur itself is not relied on for these text-contrast bounds.
Primary-button white text has at least 4.82:1 at rest and 4.63:1 on hover.
Reduced-motion support remains. Reduced-transparency and increased-contrast media
queries restore solid workspace, cards, inset areas, controls, floating surfaces
and their original secondary text palette; increased contrast also strengthens
borders. These CSS fallbacks do not change application settings.

The 2026-10-16 frontend motion pass adds GSAP-powered active-discovery pulses and
shared masked edge reflections without altering the native view or tint opacities.
It replaces dashed discovery/drop frames and extends the glass rims to controls
and floating surfaces. See [UI motion details](DESKTOP_UI_V2.md#discovery-pulse-and-reflective-edges-2026-10-16).

WebKit buttons use `appearance: none`, preventing light OS button chrome from
overriding the dark surfaces. This native-rendering issue was found during testing.

On macOS, Reduce Transparency is checked at startup, page completion and window
activation; changing it in System Settings is picked up on returning to DropFlow.
The native effect is removed and the solid CSS state restored. The CSS media query
also covers supporting WebViews. On Windows, native Mica owns its system fallback
for accessibility/remote sessions; library-reported unsupported-platform failures
leave CSS solid. DWM's low-level HRESULTs are not propagated by the existing crate,
so Windows initialization is best-effort and still requires hardware validation.
Linux uses solid surfaces. No optional settings toggle or new IPC is introduced.

## Distribution implications

Tauri's private transparent-WebView API makes this macOS configuration ineligible
for Mac App Store submission. Ordinary distribution/signing/notarization settings
are unchanged, but no signed/notarized release was produced or validated here.
Sidebar is supported by the crate on macOS 10.10+; older supported macOS versions
were not tested. This feature does not implement macOS 26+ Liquid Glass.

## Glass visibility diagnosis and evidence (2026-10-13)

The native backdrop was functioning, but the 76% sidebar tint obscured its color
influence. The 92% workspace and uniformly flat card fills reduced visual depth;
the ordinary titlebar introduced a separate opaque gray strip. Following window
activation also allowed the original material to change when inactive. These are
presentation contributors, not evidence of failed discovery or transfer logic.

Temporary native diagnostics on macOS 27.0.1 (26A434) read `opaque=false`, a clear
NSWindow background, a full 1280x820 effect view, `material=7` (Sidebar), `state=1`
(Active), `blend=0` (BehindWindow), and `hidden=false`. Actual Web Inspector and
the temporary computed-style overlay confirmed transparent html/body/root/shell;
the diagnostic sidebar was also fully transparent. The original workspace was
`rgba(16, 19, 23, 0.92)`. No native material application error was logged.

Brave alone hosted a local bright/dark/blue-orange-pink backdrop fixture. Safari
was not accessed. The user directly confirmed that the diagnostic sidebar showed
blurred colors, then separately confirmed the final 62% tinted sidebar was
"visible and readable". This is the on-screen evidence of genuine desktop
interaction. Isolated window captures flattened that backdrop to near-uniform
gray even with the diagnostic sidebar fully transparent; those captures are NOT
presented as proof of live color diffusion. Temporary probe CSS, DOM overlays
and AppKit logging are removed from the final source and bundle.

Compared with the baseline native capture, final captures show continuous chrome
without the gray titlebar seam, clearer graphite card depth, lighter selected
device edges, subtle drop-zone illumination and consistent reflective buttons.
The concept's more illustrative device artwork remains a discrepancy: the
existing type-driven SVGs are preserved, not replaced or inferred from names.

## Full-window extension verification (2026-10-13)

This pass changes shared frontend material tokens, their accessibility fallbacks,
and the Appearance description only. No Rust, native configuration, providers,
Tauri commands, assets, dependencies, or update/release behavior is changed.
`AGENTS.md` was separately reconciled with the inspected desktop/Android codebase,
actual command working directories, and continuation on the designated branch.
The old mandatory-new-branch rule no longer conflicts with an approved continuation;
approval, preservation of unrelated work, focused scope and release safety remain.

- `pnpm tsc --noEmit`, `pnpm build`, and `pnpm test:ui` passed. A local
  production-assets debug `.app` was rebuilt at the existing bundle path with
  updater artifacts disabled by a command-line override. No additional installed
  app identity, release bundle, push, merge or publication was created.
- Actual native Home, Devices, Transfers and Settings/Updates were inspected.
  Appearance was reviewed in light mode, then the original dark preference was
  restored. Update channels and all six saved history entries were preserved.
- The clear-history confirmation opened with a visible Cancel focus ring and a
  denser floating material. Escape dismissed it without clearing history.
- The user directly confirmed the workspace, cards and right panel over Brave's
  colored fixture: "Yes, glass is visible and readable". This establishes the
  full-window on-screen effect; isolated captures still cannot prove live diffusion.
- Native zoom/restore exercised layout repainting. As in the earlier pass, some
  isolated captures contained stale navigation/tab/theme layers; settled captures
  after zoom/restore were correct. The cause has not been established.
- Files receives the same shared card/control tokens, verified in source. Its
  previously identified native filesystem hang remains unresolved and blocks a
  complete native Files visual acceptance. It was not retriggered or fixed here.
- Reduced-motion code remains intact. Solid reduced-transparency/increased-contrast
  fallbacks were inspected in CSS; OS accessibility preferences were not changed.
  Color-compositing checks over black/white bounds informed the text palette, but
  this is not a complete accessibility or performance certification.
- No Android build, broad backend tests, physical transfer test, Windows run,
  signing/notarization or updater installation was performed in this frontend pass.
  Safari was not accessed; Brave alone hosted the local diagnostic backdrop.
  The diagnostic tab/window was closed after validation; the user's existing
  Brave window was preserved.

Current local captures are under `/tmp/dropflow-full-window-glass-evidence/`,
including `home-final.png`, `transfers-final.png`, `settings-updates-final.png`,
`confirmation-dialog-final.png`, and `light-settings.png`. These are diagnostic
artifacts, not release assets. The preceding native-probe evidence and broader
checks below belong to the earlier sidebar fidelity pass, not this extension.

## Earlier verification (2026-10-13, sidebar fidelity pass)

- `pnpm tsc --noEmit`, `pnpm build`, and `pnpm test:ui` passed.
- `cargo check --locked --offline` passed; `cargo test --locked --offline --lib`
  passed all 44 tests.
- A production-assets debug `.app` bundle
  built successfully using `pnpm tauri build --debug --bundles app` with a
  command-line `createUpdaterArtifacts: false` override (no release publication).
- Actual native Home, Devices, Transfers and Settings/Updates were inspected.
  Bright/dark/color backdrop captures were collected, subject to the capture
  limitation above. Inactive controls turned gray while content remained legible.
  Native zoom changed capture dimensions from 2560x1640 to 3024x1744 and restored
  correctly. Native file picker opened/cancelled; minimize and restoration through
  the system Window menu were exercised. Drag actions succeeded without a tool
  error, but isolated captures do not prove global window displacement. The edge
  resize attempt did not establish a size change; free edge resize and Finder
  file-drop remain manual acceptance checks.
- Files navigation encountered an existing backend hang. A one-second process
  sample showed the main thread in `fs_browser::list_directory`, then
  `std::fs::read_dir`, `__opendir2`, and `__open_nocancel`. No filesystem/backend fix was made in this UI
  task. The temporary app was restarted; Files native visual validation is blocked
  by that issue. Its shared tokens still receive the same material changes.
- No formal GPU/frame-time/battery profiling, OS preference toggle test, physical
  transfer test, Windows run, or signed release test was performed. No persistent
  render artifacts were observed in settled captures. Some captures contained
  stale navigation/tab layers, which cleared after native zoom/restore; the actual
  cause of that capture/repaint artifact was not established. This is not a
  performance certification.

Local evidence is under `/tmp/dropflow-glass-evidence/`: `before-bright.png`,
`after-home-color.png`, `after-home-bright.png`, `after-home-dark.png`,
`after-devices.png`, `after-transfers.png`, `after-settings-updates.png`,
`after-zoom.png`, and `after-inactive.png`. These are local test artifacts, not
release assets or checked-in screenshots. No live network settings or macOS
system preferences were changed. Backend, providers and updater contracts are
unchanged; no changes were pushed and no release was published.

The maintainer subsequently clarified that Files works normally after granting
folder permission (2026-10-16). The earlier blocked filesystem observation above
is historical validation evidence, not a confirmed outstanding Files defect.
No filesystem implementation or permission settings were changed in this pass.
The Beta 17 local native check subsequently opened Files and successfully listed
the Desktop folder. About showed `0.1.0-beta.17 · beta` while the saved update
channel remained Development.

Remaining platform acceptance: complete Reduce
Transparency, free dragging, edge-resizing, fullscreen and file-drop checks on
macOS, and separately validate Mica/system fallbacks and Snap/resize/drop behavior
on Windows 11.

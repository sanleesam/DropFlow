# Native window materials

Focused extension of UI v2. No React/provider, transfer, discovery, protocol,
updater, command, event, icon or release behavior is changed.

## Implementation and research

- macOS uses one `NSVisualEffectView` with `UnderWindowBackground` and
  `BehindWindow` blending. Apple explicitly describes this material as peeking
  through the window background. `Sidebar` was considered, but this view spans
  the window, so the whole-window semantic is a better fit. It is not a separate
  native effect for each HTML panel. Activation follows the normal window state.
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
- [Apple under-window material](https://developer.apple.com/documentation/appkit/nsvisualeffectview/material-swift.enum/underwindowbackground)
- [Apple visual effect views](https://developer.apple.com/documentation/appkit/nsvisualeffectview)
- [Apple Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass)
- [Microsoft Acrylic](https://learn.microsoft.com/en-us/windows/apps/design/style/acrylic)
- [Microsoft Mica](https://learn.microsoft.com/en-us/windows/apps/design/style/mica)

## Surface integration and fallback

The native window is transparent; `tauri.conf.json` enables `macOSPrivateApi`,
and Cargo enables `macos-private-api`. The flag is macOS-only in effect but is
kept in shared config so Tauri's Cargo feature/allowlist validation agrees on
every build host; no per-host manifest rewrite is needed. Decorations, titlebar,
controls, shadows, size constraints and file-drop configuration are unchanged.

CSS stays opaque in browser previews and before native initialization. Native
code publishes `data-native-material` only after application returns success,
including after a page reload. Supported material states make html/body/root/shell
transparent. Titanium sidebar opacity is 76%, workspace 92%, contextual panel
82%. Cards, inputs, dialogs and the drop area remain opaque for legibility.
There is no CSS backdrop blur. Small sidebar text is slightly brighter to preserve
contrast over bright content. Light-mode preferences remain supported, with a
94% sidebar overlay and darker secondary text. Worst-case flat white behind the
dark sidebar gives 5.05:1 for subtle text; flat black behind the light sidebar
gives 4.61:1. Native blur itself is not relied on for these text-contrast bounds.

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
UnderWindowBackground is macOS 10.14+; the crate falls back to AppearanceBased
on older supported macOS versions. Older versions were not tested.

## Verification (2026-10-13)

- `pnpm tsc --noEmit`, `pnpm build`, and `pnpm test:ui` passed.
- `cargo check --locked --offline` passed; `cargo test --locked --offline --lib`
  passed all 44 tests.
- `pnpm tauri dev` compiled and started. A production-assets debug `.app` bundle
  built successfully using `pnpm tauri build --debug --bundles app` with a
  command-line `createUpdaterArtifacts: false` override (no release publication).
- Actual macOS 27.0.1 native bundle inspected, not just a browser. Logs confirm
  native mode 1; Web Inspector confirms `data-native-material="vibrancy"` and
  transparent body/root layers. Corrected dark WebKit buttons, normal titlebar,
  native zoom and file-picker open/cancel were checked.
- A separate white/blue/black browser window was prepared for backdrop testing.
  Captures did not conclusively demonstrate live desktop diffusion. This is NOT
  counted as visual proof of desktop interaction. Manual on-screen verification
  over bright/dark/colorful wallpapers and windows remains an acceptance gate.
- Pointer automation reported `noWindowsAvailable` for locally built bundles,
  including a temporary separate test identity. Free drag, edge resize and
  Finder-to-window file drop could not be verified. The test identity was a
  command-line build override, not a repository configuration change.
- No formal GPU/frame-time/battery profiling, OS preference toggle test, physical
  transfer test, Windows run, or signed release test was performed. No persistent
  render artifacts were observed in settled captures; inspector opening briefly
  produced an incomplete frame. This is not a performance certification.

Before release: complete the manual backdrop, Reduce Transparency, dragging,
edge-resizing, minimize/fullscreen and file-drop checks on macOS, and separately
validate Mica/system fallbacks and Snap/resize/drop behavior on Windows 11.

### A new look, shared with desktop

Android now carries DropFlow's Titanium + Icy Blue design: cool graphite,
reflective silver edges, clear device cards, calmer discovery and a new
silver/blue app mark. Home, History, Settings and About have been rebuilt in
native Compose, with layouts that scroll and keep long names under control.

### Sharing feels connected

Incoming requests and live progress follow you across every screen. Save your
device name explicitly, reduce animations when you prefer, and see transfer
results without returning to Home. Received files live in Downloads / DropFlow;
duplicate names are kept as separate copies. History and trusted devices apply
to the current app session.

### Reliability

Stronger manifest and checksum checks, success acknowledgment after files are
saved, cleanup on failure, and protection against overlapping incoming prompts.
The DFP/1 wire format remains compatible with existing desktop builds.

### Before publishing

- Test Android ↔ macOS and Android ↔ Windows on physical devices, including SAF
  selection, accept/reject, cancel, disconnect, timeout and multiple files.
- Verify an in-place upgrade from Android Beta 1 with the same signing key.
- Check Android 8/9 storage permission behavior and current-device layouts.

Local validation includes debug/release builds, lint, 14 regression tests and
emulator receive checks. These checks do not establish physical-device discovery
or bidirectional interoperability. A signed release is required for distribution.

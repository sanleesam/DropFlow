# DropFlow 0.1.2-beta.21 — desktop safety patch

This is a **Beta patch**, not a Stable release. Android's version and release
channel are unchanged.

## Important behavior change

Remembered device names/IDs are not authenticated by DFP/1. Automatic acceptance
based on remembered devices has been disabled; existing remembered records are
preserved. Incoming files require approval unless you explicitly turn off
"Ask before accepting" (which permits anyone who can reach DropFlow to send).
Authenticated pairing is not implemented, and transfers are not encrypted.

## Fixes

- Replace platform-dependent cross-handle socket interruption with cancellable
  reads/writes. The 75-second approval and 15-second transfer inactivity budgets
  remain; short internal polling does not change DFP/1 framing or add a reader.
- Verify cancellation after reads are actually pending, cancellation of blocked
  writes, timeout preservation, fragmented frames and a complete DFP/1 round trip
  on both operating systems before creating a release tag.

- Deleting or renaming a symbolic link no longer changes its original target.
  Renames and received-file finalization use exclusive, non-replacing moves.
- Simultaneous receives with matching names preserve both files with collision
  suffixes rather than overwriting either one. History records actual saved paths.
- Incoming failures reliably finish their UI state and partial-file cleanup;
  failed transfers now remain visible in the Failed list and persisted history.
- Competing incoming approval requests are explicitly rejected as busy instead
  of replacing the current prompt. Connections/transfers are bounded, and active
  session IDs cannot replace another session's cancellation state.
- Cancellation interrupts blocked socket operations, including sender approval
  waits. Cancelling an unknown/finished ID no longer cancels unrelated transfers.
- 100% byte progress shows "Finishing" until final acknowledgement. Late progress
  cannot revive cancelled/failed operations or undo a verified completion.
- Windows received-file opening uses the native system opener, not cmd.exe.
  Automatic opening is limited to the received folder, never received content.
- Partial filenames are bounded hashes, avoiding session-name aliases/long names.
  Windows-reserved names are sanitized consistently; valid double-dot names can
  be renamed without allowing traversal.
- The home-scoped Files browser no longer starts at an unsupported external
  receive directory; Settings provides a system-folder Open action.
- Preserve the independent notification preference when other settings change;
  start in dark mode while saved preferences load.
- Enable a local-only production Content Security Policy. Remove the unused,
  uncleared legacy illustration pack without changing the active vector UI.
- Bundle license/attribution notices in desktop installers. macOS gets a valid
  whole-app ad-hoc signature, including sealed resources. Beta release artifacts
  stay draft until both platform jobs and merged updater metadata succeed.

## Still experimental

macOS is ad-hoc signed, **not Developer ID signed/notarized**. Windows installers
do not have Authenticode publisher signing. OS trust warnings may remain; updater
signatures are a separate integrity mechanism. Real current-build Mac↔Windows
acceptance, multi-GB/interruption scenarios and all supported OS versions still
need hardware testing. This release does not claim Stable readiness.

Empty directories/symlinks are not transferred, metadata is limited to 64 KiB,
and a user-facing resume workflow is not implemented. The Files browser remains
home-scoped; external receive folders can be opened in the system file manager.

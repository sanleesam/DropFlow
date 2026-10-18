# Android Beta 2 preparation

Prepared on 2026-10-19 on `codex/android-beta2-titanium`.
Android version: **0.1.0-beta.2**, versionCode **18** (Beta 1 was 17).
Desktop source and desktop release versions are unchanged. Nothing has been
pushed, tagged or published by this preparation pass.

## Desktop design translated to native Android

All four screens, incoming prompts, navigation, active progress and empty states
use a shared Compose design system. The values come from desktop `desktop.css`
and `App.css`: background `#101317`, surfaces `#171C23` / `#1A2028`, inset
`#141A21`, border `#343E49`, text `#EEF3FC` / `#B6C0CD`, accent `#2F7CF6`,
icy highlights `#B7DCFF`, and matching success/error colors.

Reflective multistop rims and a faint upper-surface reflection provide the
same material cues as desktop. Corners are 12–14 dp, controls have generous touch
targets, and the page width is bounded for larger screens. Native Compose draws
the discovery rings and short page/selection transitions. The saved Reduce
animations preference stops the rings and page/selection animation; system
animation scaling also applies. The desktop's existing transparent silver/blue
mark is reused in the toolbar, About and adaptive launcher icon.

macOS vibrancy, Mica and GSAP are platform-specific. Android uses native Compose
layers and motion; it does not embed a WebView or claim to blur the Android
wallpaper. The readable graphite surface is consistent on Android 8+. Primary buttons use
the desktop strong blue `#2563EB` for 4.64:1 contrast with silver text. Typography
uses inherited content colors, switches have accessible labels, and outcome
messages use a dark surface and do not replay after rotation.

## Beta 1 issues addressed

| Issue | Change |
| --- | --- |
| Incoming prompts and progress only on Home | App shell owns the prompt, progress and results across every page. |
| Long names crowd actions | Weighted text, bounded lines, ellipsis, scrollable content and a scrollable receive sheet. |
| Refresh retains stale device selection | Rescan clears devices and selection together. |
| Discovery/history callbacks can overwrite state | Atomic state updates preserve concurrent fields/events. |
| Renaming writes on every keystroke | Local draft plus explicit valid-name save; blank names cannot be saved. |
| Unconnected overwrite, auto-open and alternate accent controls | Removed from the interface; receive behavior and fixed desktop appearance are described accurately. Stored legacy preference values are retained. |
| Session-only history/trust described as permanent | Copy explicitly describes the current app session. |
| Receiver trusts changed metadata | Validate counts, indices, totals, names, sizes and zero resume offset against the accepted manifest. |
| Empty/mismatched completion checksums accepted | Require and verify a SHA-256 completion digest; streaming desktop metadata may still leave the initial digest empty. |
| Sender receives success before storage publication | Send final ACK only after all targets publish successfully; clean up failures, including published legacy targets and failed MediaStore allocation. |
| Second incoming prompt replaces the first | Reject overlapping incoming requests without losing the original callback. |
| Incoming session state remains stale | Forward connection lifecycle changes to TransferEngine. |
| Rapid outgoing start can race | Atomically reserve the outgoing send flag. |
| Cancellation/error feedback is generic | Retain the receiver failure reason, report cancellation separately, and publish incoming outcomes to the global snackbar. |
| Storage API-level lint error | Annotate the already guarded API 29 storage helper. |
| Release workflow can change signing key on every build | Fail without all persistent signing secrets; run tests/lint; use version-specific notes. |

The transfer engine, NSD discovery and DFP/1 framing remain the existing
implementation. Receiver checks enforce the existing desktop contract; there is
no new protocol version or wire-format change.

## Validation performed

- Clean Android debug and unsigned release APK builds succeeded.
- Android lint passed with zero errors; remaining warnings cover older dependency
  versions, icon metadata, locale formatting and Compose parameter conventions.
- 14 receiver validation unit tests passed (manifest bounds/totals, filename/size
  substitution, unsupported resume and missing/wrong SHA-256).
- Explicit rename and its saved value survived Activity recreation; reduced-motion
  preference and one-time result delivery were checked across rotation.
- Native emulator screens inspected: Home, History, Settings, About and incoming
  prompts. Layout checks include increased font size and landscape.
- Emulator DFP/1 peer checks: multi-file receipt with an empty file; exact stored
  bytes after final ACK; prompts on Settings/History; decline; invalid manifest;
  substituted header; checksum failure and cleanup; sender cancellation; second
  simultaneous request; progress on About/History; local cancellation and cleanup.
- Runtime crash log was checked; no app crash occurred during these checks.

Reproduce builds in `apps/android`:

```sh
./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease
```

With the debug app open in an emulator and confirmation enabled:

```sh
python3 scripts/verify-android-receiver.py --serial emulator-5554
```

The integration script uses an emulator-local forwarded TCP port. It never
changes host network settings. Screenshots default to `/tmp/dropflow-android-qa`;
this pass's transfer evidence is in `/tmp/dropflow-android-beta2` and layout/
final smoke screenshots are in `/tmp/dropflow-android-qa`.

## Release gates

Preparation is complete; publication remains gated by:

1. Physical Android ↔ macOS and Android ↔ Windows tests: discovery, SAF send,
   receive, multi-file, accept/reject, timeout, disconnect, cancellation and hashes.
2. Android 8/9 legacy storage permission/cleanup checks. The emulator check used a
   current Android image and does not cover this branch.
3. Sign with the retained Android Beta 1 key and verify an in-place upgrade.
   Local `app-release-unsigned.apk` is a build validation artifact; the debug APK
   is a preview and uses the debug signing key. If Beta 1's ephemeral key was
   discarded, a normal upgrade cannot be established without recovering it.
4. Review the prepared [release notes](releases/android-0.1.0-beta.2.md), then tag
   `android-v0.1.0-beta.2` only after these checks pass.

History and trusted devices are still session-only. Android background receiving,
notifications, persisted history/trust and seekable SAF resume remain future work.
Keep DropFlow open for this beta's sharing workflow.

## Local commits and worktree

- `9a49be0`: native Titanium/Icy Blue interface and shared shell.
- `adaf2df`: receiver fixes, validation tests, emulator checks and Android version bump.
- Release workflow, notes and this preparation report are recorded in the following
  release-preparation commit.

The pre-existing deletion of `TASKS.md` was left untouched and unstaged.

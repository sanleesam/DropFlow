# Historical pause checkpoint — resumed 2026-11-06

The user explicitly resumed on 2026-11-06. The record below describes the paused
2026-11-05 state, not current release status. Follow newer validation/release notes
for the Beta 21 continuation; never move the original Beta 20 tag.

Saved at the user's explicit request on 2026-11-05 (Asia/Kolkata).
**Do not continue debugging, rebuild/retry CI, merge, or publish until the user resumes.**

## Repository state

- Checkout: `/Users/sanleesam/Desktop/DropFlow`.
- Branch: `codex/desktop-safety-patch`.
- Implemented candidate commit: `df6b3b68ce63a99b1e0908a7e1d52b9fea66a82a`.
- Remote main is still `7826e1940b4e392b732a0d861ddef80b14c7a7d0`.
- Open, unmerged PR: https://github.com/sanleesam/DropFlow/pull/7
- Candidate tag `v0.1.2-beta.20` points to df6b3b6. Preserve it; do not force-move
  or reuse it for changed source. A source fix should use a new version/tag.
- Existing user deletion ` D TASKS.md` remains unrelated, unstaged and uncommitted.
- Android source and release remain unchanged.

The checkpoint commit itself only adds this document after the candidate commit.

## Implemented and committed

1. `119467f`: native exclusive moves prevent receive/rename overwrite races;
   link deletion/rename preserves the selected link rather than acting on target;
   Windows received-file opening uses the native opener, not cmd.exe.
2. `c3ed0b9`: disable unauthenticated remembered-peer auto-accept, preserve records,
   bound connections/transfers, prevent session-ID replacement, reject competing
   prompts as busy, centralize receive errors and partial cleanup, cancellation
   monitoring, bounded partial names, finalization/late-event UI safeguards.
3. `feb5f76`: retain failed history/Failed-filter results, add legacy-settings
   migration/cleanup coverage and an Open receive-folder action.
4. `bf6bc9d`: remove unused uncleared illustration PNG pack/imports without changing
   the active original vector UI; correct attribution/resume-scope documentation.
5. `df6b3b6`: target `0.1.2-beta.20` across four manifests, production CSP,
   complete macOS ad-hoc bundle signing, bundled license notices, Beta draft-first
   publication gates, updated test plan and release/validation notes.

Details: `docs/DESKTOP_0.1.2_VALIDATION.md` and
`docs/releases/desktop-0.1.2-beta.20.md`.

## Passed locally / native macOS

- 69 Rust library tests, TypeScript, production build, 21 lifecycle scenarios,
  21 UI assertions, 12 desktop version/resolver/shared-workflow tests and YAML
  parsing passed. Production npm advisory scan reported no known vulnerabilities.
- Isolated native build used bundle ID `com.dropflow.validation.patch20`, never
  the installed application or its state. Verified real approval for a remembered
  peer, valid 1 MiB checksum/final-ACK receipt, malformed-header rejection with
  persisted Failed history, BUSY rejection retaining the original prompt and
  explicit Decline. Partial-session state ended empty.
- Complete macOS bundle passed deep/strict codesign verification, with bound
  Info.plist and sealed resources. It is ad-hoc, NOT Developer ID/notarized.

## Release stopped — unresolved Windows failure

Run: https://github.com/sanleesam/DropFlow/actions/runs/42033253453

- macOS job `127264741094`: **success**, including bundle-signature check.
- Windows job `127264740789`: **failure** at Rust Regression Checks.
- Windows result: 66 passed, 1 failed (67 total; Unix-only tests are excluded).
- Failing test:
  `transfer_manager::lifecycle::tests::cancellation_interrupts_a_blocked_socket`
- Failure location reported by CI: `src/transfer_manager/lifecycle.rs:123`.
- Assertion: `start.elapsed() < std::time::Duration::from_secs(1)`.
  Windows run finished this test at approximately the 3-second read timeout.
- This has NOT been diagnosed/fixed. Do not merely weaken the test or assume the
  cancellation behavior works on Windows because it passed on macOS.
- `sync-beta-channel`: **skipped**, as intended. No retry was started on pause.

Verified live at pause: the public Beta feed still advertises
**`0.1.1-beta.19`**, with both artifact URLs referencing `v0.1.1-beta.19`.
Public release lookup for `v0.1.2-beta.20` returned 404; it is not a published
public release. macOS assets may exist in an Actions-created draft; inspect that
draft when resuming rather than treating it as a completed release.

## Resume sequence (only after user resumes)

1. Recheck branch/working tree, main/PR state and live release/channel state.
2. Diagnose and fix the Windows blocked-socket cancellation failure with real
   Windows CI evidence. Preserve cancellation isolation, approvals and protocol.
3. Add/update regression tests, rerun local checks and choose a NEW immutable
   source version/tag for the fix (do not move the existing Beta 20 tag).
4. Run both platform gates. Publish only if both pass; otherwise leave Beta feed
   untouched. Do not bypass the draft/merged-manifest safety gate.
5. Verify downloaded Mac/Windows updater signatures, Mac bundle resources/version/
   icon/notices and the merged versioned/floating feed before claiming release.
6. Merge PR with `[skip ci]` to avoid an unrelated automatic Development release
   only after successful release verification. Then record the final evidence.

## Local diagnostic files / cleanup

- Native QA process is stopped, and the generated `DropFlow Validation.app`
  bundle was removed from the debug bundle directory.
- Disposable fixture/state: `/private/tmp/dropflow-patch20-receive` and
  `~/Library/Application Support/com.dropflow.validation.patch20`.
- Artifact verification helper prepared but NOT executed:
  `/private/tmp/dropflow-verify-beta20.mjs`. It uses the existing compiled Rust
  Minisign helper `/private/tmp/dropflow-semver-verify`; public key is derived
  from the checked-in configuration. Adapt to the eventual new release version.
- No Safari use, host network/accessibility changes, installed app replacement,
  signing-key rotation, Stable promotion or Android publication occurred here.
- A separate `caffeinate -i -w ...` process was observed at pause but was not
  started by this work; it was left untouched.

Stable remains a no-go: physical current-build Mac↔Windows acceptance,
multi-GB/interruption coverage, OS publisher signing and Stable-channel migration
remain separate gates. No authenticated pairing/encrypted transport or user-facing
resume workflow was introduced by this patch.

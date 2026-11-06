# Desktop 0.1.2 safety-patch validation — 2026-11-05/06

The native checks below describe Beta 20 on 2026-11-05. Its Windows cancellation
failure prevented publication. The continuation targets Beta 21; it does not
retroactively count Beta 20 as released or claim its native checks were rerun.

## Scope

Safety patch from desktop Beta 19; Android unchanged. DFP/1 framing, provider
architecture and signing/updater identity are preserved. The existing TASKS.md
deletion is unrelated and excluded from commits. Installed DropFlow.app was not
replaced; native checks used `com.dropflow.validation.patch20` and disposable data.

## Fix/verification mapping

- Link-target deletion/rename: regression covers target preservation, renamed
  symlink identity and removal of a dangling link. New names cannot escape the
  parent via platform separators/prefixes or reserved aliases.
- Exclusive filesystem moves: race tests verify exactly one same-destination
  move succeeds, and concurrent publish-with-collision retries preserve both
  received files plus an existing target. Unsupported filesystems fail closed;
  there is no replacing-rename fallback.
- Trust: legacy remembered records survive, but the auto-accept flag migrates to
  false. Approval policy does not grant authorization from a claimed name/UUID.
  Explicit allow-all remains separate and is described as such in Settings.
- Lifecycle: one error-reporting boundary handles every established incoming
  session result, with RAII partial cleanup. Unique-session/concurrency checks,
  bounded socket permits and cancellation wakeup have unit coverage.
- UI: 21 session scenarios cover failure history/deduplication, 100% finalization,
  late event isolation and existing path propagation; 21 primitive assertions.
- Production frontend build emits only the current logo, CSS and JS, not the
  removed legacy device/file illustration PNG pack. Source notices are bundled.
- Native production-assets debug bundle passed strict deep codesign verification:
  ad-hoc identity, bound Info.plist and sealed resources. This is not notarization.

## Native macOS checks performed

- Actual app rendered under the production CSP, loaded the isolated persisted
  settings, and displayed `0.1.2-beta.20 · beta`.
- A seeded remembered peer with legacy auto-accept true was migrated to false;
  its incoming request still showed the real approval prompt. Record preserved.
- Accepted a 1 MiB fixture through the native prompt: exact bytes/SHA-256 passed
  and final DFP/1 acknowledgement arrived. Completed history appeared normally.
- Rejected an unapproved file header after native acceptance: no final/partial
  file remained. After a validation-driven history fix, the native Home recent
  list and Transfers → Failed filter both retained the failed result. Persisted
  state contained one success, one failure and zero incomplete sessions.
- A second incoming request received an explicit BUSY rejection while the
  original `probe/decline.bin` prompt remained visible; declining the original
  completed its synthetic peer's rejection check.
- An earlier unattended probe timed out while testing. It is recorded as an
  approval timeout, not counted as a successful transfer.

Disposable native fixtures were under `/private/tmp/dropflow-patch20-receive`.
No OS/network/accessibility settings or physical-device data were changed.

## Remaining acceptance / release evidence

### Beta 21 continuation — 2026-11-06

Windows CI run 42033253453 reproduced a failure to interrupt a pending blocking
read using a cloned socket's shutdown. Mac passed; Windows failed one test and
the Beta channel sync was skipped, preserving Beta 19. The Beta 20 tag is retained.

Beta 21 replaces that monitor with a single-owner cancellable Read/Write adapter.
The socket uses nonblocking I/O plus native readiness waits capped at 50 ms.
Only WouldBlock is retried, never a blocking Winsock timeout (Microsoft documents
that as an indeterminate connection). The existing 75-second approval / 15-second
inactivity budgets remain. Cancel returns ConnectionAborted rather
than Interrupted, which read_exact/write_all would silently retry. No additional
reader, socket clone, wire-format change, or background watcher thread is used.

Local Rust suite passes 74 tests, including a genuinely pending read cancelled
within one second (the original Windows gate is not weakened), a blocked write,
fragmented reads, inactivity budgets, a 32 MiB byte-for-byte backpressure test
and a complete DFP/1 TCP round trip across the adapter. Both-platform PR checks
run before creating a new tag; final results are recorded below.

Beta 21 local frontend checks also pass: TypeScript/production build, 21 lifecycle
scenarios, 21 UI primitive assertions and 13 release-tooling tests. Windows has
two fewer Rust tests because Unix symbolic-link tests are platform-specific.
Readiness API follows [polling's registration contract](https://docs.rs/polling/3.11.0/polling/struct.Poller.html).
The blocking-timeout restriction is documented by
[Microsoft Winsock](https://learn.microsoft.com/en-us/windows/win32/winsock/sol-socket-socket-options).

Historical Beta 20 local checks passed: 69 Rust library tests, TypeScript, 21 lifecycle
scenarios, 21 UI primitive assertions, production frontend build, and 12 desktop
version/resolver/shared-workflow tests. Workflow YAML and whitespace checks pass.

### Published Beta 21 evidence — 2026-11-06

- Source/tag: `v0.1.2-beta.21` resolves to
  `76d6534ca3b1c9bddeed9cbcab0469da24a0345d`. Beta 20's original tag was not moved.
- [Read-only preflight run 42200249975](https://github.com/sanleesam/DropFlow/actions/runs/42200249975)
  passed on macOS (74 Rust tests) and Windows (72). Both platforms passed the
  blocked-read/write cancellation and 32 MiB byte-integrity regressions.
- [Release run 42200979554](https://github.com/sanleesam/DropFlow/actions/runs/42200979554)
  passed both platform jobs and channel sync. The release is public, prerelease
  true, release ID `436358947`, published `2026-11-06T10:24:08Z`.
- Downloaded versioned and floating Beta manifests are identical, advertise
  `0.1.2-beta.21`, and contain all four darwin/windows updater aliases. Artifact
  URLs point at the immutable versioned release, not a floating installer.
- Downloaded both updater artifacts and verified their Minisign signatures
  against the updater public key embedded in the Tauri configuration.
- Extracted the downloaded Mac archive and passed strict deep codesign
  verification. Bundle version is `0.1.2-beta.21`; embedded `icon.icns`, `LICENSE`,
  `NOTICE`, and `THIRD_PARTY_NOTICES.md` match source bytes. Removed the extracted
  test app afterward; `/Applications/DropFlow.app` was not replaced.
- [PR #7](https://github.com/sanleesam/DropFlow/pull/7) merged only after these checks,
  as `9f460a2f1bf6e14fddc403f66a08cd09903daafb`, using `[skip ci]` to avoid an
  unrelated Development release. Android source/version/release are unchanged.

Downloaded artifact SHA-256 values (also match GitHub's asset digests):

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `DropFlow_aarch64.app.tar.gz` | 6704512 | `51a23ec95e5f7d1783074e330f6bac669f259ff479c351925fe5e50127466769` |
| `DropFlow_0.1.2-beta.21_x64-setup.exe` | 4347907 | `da96ac4bf945bb4b22d21dec32ddd32219dbd670b87c843269c47aa198607c40` |

### Remaining acceptance limits

These checks do not replace physical Mac↔Windows/Android LAN proof.
No new physical-device, multi-GB, Developer ID/notarization or Authenticode proof
is claimed. The browser still deliberately has a home boundary; it does not gain
arbitrary filesystem write access from this patch. Local malicious filesystem
ancestor-swapping attacks are not claimed formally solved by path prechecks.

This remains Beta. Stable channel publishing/migration and authenticated pairing
are not introduced by this patch. See the versioned release notes for the
security behavior change and remaining limitations.

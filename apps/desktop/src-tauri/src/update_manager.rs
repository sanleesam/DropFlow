//! Channel-aware application update engine.
//!
//! The Tauri updater plugin reads its endpoints from static configuration, but
//! `UpdaterExt::updater_builder()` allows overriding the endpoints at runtime.
//! This module uses that supported mechanism to fetch the update manifest of
//! whichever channel the user selected in Settings → About (see
//! `state_manager::UpdateChannel`):
//!
//! - `Beta`        → `…/releases/download/beta/latest.json`
//! - `Development` → `…/releases/download/development/latest.json`
//!
//! Two deliberate deviations from the plugin defaults live here:
//!
//! 1. **Channel-aware version comparison.** Tauri's default test is plain
//!    semver ordering (`candidate > current`), which mis-orders cross-channel
//!    pre-releases. `UpdaterBuilder::version_comparator` (available in
//!    tauri-plugin-updater 2.10.1) is used instead — see
//!    [`should_offer_update`].
//! 2. **Channel/generation binding.** A pending update is only valid for the
//!    selection epoch it was fetched in; channel switches bump a generation
//!    counter so stale checks, stale pending updates and in-flight downloads
//!    can never surface or install the wrong channel's build.

use serde::Serialize;
use std::sync::Mutex;
use std::time::Duration;
use tauri::{AppHandle, Emitter, State};
use tauri_plugin_updater::UpdaterExt;

use crate::state_manager::{AppStateContainer, UpdateChannel};

/// Update information returned to the frontend after a channel-aware check.
#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    pub available: bool,
    pub current_version: String,
    pub available_version: Option<String>,
    pub release_notes: Option<String>,
    pub pub_date: Option<String>,
}

// ─── Channel-aware version comparison (Blocker 1) ────────────────────────────

/// The release line encoded in a version's pre-release identifiers, e.g.
/// `0.1.0-dev.125` → `Development(125)`, `0.1.0-beta.16` → `Beta(16)` and
/// `0.1.0` → `Stable`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ReleaseLine {
    Development(u64),
    Beta(u64),
    Stable,
}

fn release_line(version: &semver::Version) -> Option<ReleaseLine> {
    let pre = version.pre.as_str();
    if pre.is_empty() {
        return Some(ReleaseLine::Stable);
    }
    let (kind, number) = pre.split_once('.')?;
    let number = number.parse().ok()?;
    match kind {
        "dev" => Some(ReleaseLine::Development(number)),
        "beta" => Some(ReleaseLine::Beta(number)),
        _ => None,
    }
}

/// Decides whether the candidate release should be offered for the installed
/// version. Used through `UpdaterBuilder::version_comparator`, replacing the
/// plugin's plain-semver acceptance test.
///
/// Rules:
///
/// - Within one release line the build number decides: only a **strictly
///   newer** build is offered (`dev.126 > dev.125`; equal and older builds are
///   rejected). Build numbers are compared numerically, so `dev.100 >
///   dev.99`.
/// - Across release lines the switch itself is the explicit user decision,
///   and each floating manifest only ever advertises that line's latest
///   build, so the selected line's release is always offered (a Beta install
///   is offered the Development build, and vice versa). The within-line rules
///   above are what prevent downgrades.
/// - A stable installation is never moved onto a pre-release, while any
///   pre-release may follow a stable promotion.
/// - Unrecognized version shapes fall back to plain semver, so no downgrade
///   is ever accepted by accident.
pub(crate) fn should_offer_update(
    current: &semver::Version,
    candidate: &semver::Version,
) -> bool {
    match (release_line(current), release_line(candidate)) {
        (Some(cur), Some(cand)) => match (cur, cand) {
            (ReleaseLine::Development(a), ReleaseLine::Development(b)) => b > a,
            (ReleaseLine::Beta(a), ReleaseLine::Beta(b)) => b > a,
            (ReleaseLine::Beta(_), ReleaseLine::Development(_)) => true,
            (ReleaseLine::Development(_), ReleaseLine::Beta(_)) => true,
            (ReleaseLine::Stable, _) => false,
            (_, ReleaseLine::Stable) => true,
        },
        _ => candidate > current,
    }
}

// ─── Channel/generation binding for pending updates (Blocker 2) ──────────────

/// Pure bookkeeping that binds pending updates to the channel selection they
/// were made under. `generation` identifies the current selection epoch and is
/// bumped every time the selected channel changes or pending state is
/// explicitly invalidated; anything captured before the bump is stale.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
struct UpdateLedger {
    generation: u64,
    /// Channel + generation the currently stored update was fetched for.
    pending: Option<(UpdateChannel, u64)>,
}

impl UpdateLedger {
    /// Bumps the generation and drops any pending update binding.
    fn invalidate(&mut self) -> u64 {
        self.generation += 1;
        self.pending = None;
        self.generation
    }

    /// Records a stored update binding if `generation` is still the current
    /// epoch. Returns `false` when the result is stale and must be discarded.
    fn store(&mut self, channel: UpdateChannel, generation: u64) -> bool {
        if generation != self.generation {
            return false;
        }
        self.pending = Some((channel, generation));
        true
    }

    /// Whether a binding for exactly this channel and epoch is still current.
    fn is_current(&self, channel: UpdateChannel, generation: u64) -> bool {
        generation == self.generation && self.pending == Some((channel, generation))
    }

    /// Releases the pending binding if it still belongs to the current
    /// selection epoch and the given channel.
    fn take(&mut self, channel: UpdateChannel, generation: u64) -> bool {
        if self.is_current(channel, generation) {
            self.pending = None;
            true
        } else {
            false
        }
    }
}

/// Runtime wrapper pairing the ledger with the actual plugin update object.
struct PendingInner {
    ledger: UpdateLedger,
    update: Option<tauri_plugin_updater::Update>,
}

/// Holds the update announced by the last check so it can be downloaded and
/// installed on demand, bound to the channel and generation it was fetched
/// for. Invalidated whenever the user switches channels.
pub struct PendingUpdate(Mutex<PendingInner>);

impl PendingUpdate {
    pub fn new() -> Self {
        PendingUpdate(Mutex::new(PendingInner {
            ledger: UpdateLedger::default(),
            update: None,
        }))
    }

    /// Bumps the selection generation and discards any pending update.
    pub fn invalidate(&self) -> u64 {
        let mut inner = self.0.lock().expect("pending update mutex poisoned");
        inner.update = None;
        inner.ledger.invalidate()
    }

    /// Stores a check result if `(channel, generation)` is still the current
    /// selection epoch. Returns `false` when the result is stale.
    fn store_if_current(
        &self,
        channel: UpdateChannel,
        generation: u64,
        update: tauri_plugin_updater::Update,
    ) -> bool {
        let mut inner = self.0.lock().expect("pending update mutex poisoned");
        if !inner.ledger.store(channel, generation) {
            return false;
        }
        inner.update = Some(update);
        true
    }

    /// Takes the pending update if it still belongs to the current selection
    /// epoch and channel; any stale update is dropped either way.
    fn take_if_current(
        &self,
        channel: UpdateChannel,
        generation: u64,
    ) -> Option<tauri_plugin_updater::Update> {
        let mut inner = self.0.lock().expect("pending update mutex poisoned");
        if inner.ledger.take(channel, generation) {
            inner.update.take()
        } else {
            inner.update = None;
            None
        }
    }
}

impl Default for PendingUpdate {
    fn default() -> Self {
        Self::new()
    }
}

/// Captures the currently selected channel together with the pending-update
/// generation, so async work can later detect mid-flight channel switches.
/// Locks are never held across an `await`.
fn current_selection(
    container: &AppStateContainer,
    pending: &PendingUpdate,
) -> Result<(UpdateChannel, u64), String> {
    let channel = {
        let state = container.state.lock().map_err(|e| e.to_string())?;
        state.settings.update_channel
    };
    let generation = pending
        .0
        .lock()
        .map_err(|e| e.to_string())?
        .ledger
        .generation;
    Ok((channel, generation))
}

// ─── Updater plumbing ─────────────────────────────────────────────────────────

fn updater_for_channel(
    app: &AppHandle,
    channel: UpdateChannel,
    timeout_ms: Option<u64>,
) -> Result<tauri_plugin_updater::Updater, String> {
    let endpoint: url::Url = channel
        .manifest_url()
        .parse()
        .map_err(|e| format!("Invalid {channel:?} manifest URL: {e}"))?;

    let mut builder = app.updater_builder();
    // Runtime endpoint override: only the selected channel's manifest is used.
    builder = builder.endpoints(vec![endpoint]).map_err(|e| e.to_string())?;
    // Replace Tauri's plain-semver acceptance test with the channel-aware rule.
    builder = builder
        .version_comparator(|current, release| should_offer_update(&current, &release.version));
    if let Some(ms) = timeout_ms {
        builder = builder.timeout(Duration::from_millis(ms));
    }
    builder.build().map_err(|e| e.to_string())
}

/// Progress payload emitted as `update-download-progress` during downloads.
#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct DownloadProgress {
    downloaded_bytes: u64,
    total_bytes: u64,
}

#[tauri::command]
pub async fn check_for_updates(
    app: AppHandle,
    container: State<'_, AppStateContainer>,
    pending: State<'_, PendingUpdate>,
    timeout_ms: Option<u64>,
) -> Result<UpdateInfo, String> {
    // Capture the channel and selection generation at check time, releasing
    // both locks before awaiting the network request.
    let (channel, generation) = current_selection(&container, &pending)?;

    let updater = updater_for_channel(&app, channel, timeout_ms)?;
    let update = updater.check().await.map_err(|e| e.to_string())?;

    // The await ran with no lock held: verify the selection is still current
    // BEFORE storing the result. A channel switch during the check must never
    // let an old-channel update leak into the new selection.
    let (current_channel, current_generation) = current_selection(&container, &pending)?;
    if current_channel != channel || current_generation != generation {
        log::warn!(
            "[UpdateManager] Discarding stale {channel:?} check result: channel switched during check"
        );
        return Ok(UpdateInfo {
            available: false,
            current_version: app.package_info().version.to_string(),
            available_version: None,
            release_notes: None,
            pub_date: None,
        });
    }

    let info = match &update {
        Some(u) => UpdateInfo {
            available: true,
            current_version: u.current_version.clone(),
            available_version: Some(u.version.clone()),
            release_notes: u.body.clone(),
            pub_date: u.date.map(|d| d.to_string()),
        },
        None => UpdateInfo {
            available: false,
            current_version: app.package_info().version.to_string(),
            available_version: None,
            release_notes: None,
            pub_date: None,
        },
    };

    if let Some(u) = update {
        if !pending.store_if_current(channel, generation, u) {
            log::warn!("[UpdateManager] Check result discarded: selection changed before store");
        }
    }

    Ok(info)
}

#[tauri::command]
pub async fn download_and_install_update(
    app: AppHandle,
    container: State<'_, AppStateContainer>,
    pending: State<'_, PendingUpdate>,
) -> Result<(), String> {
    // Take the pending update only if it still belongs to the channel and
    // generation the user has selected right now.
    let (channel, generation) = current_selection(&container, &pending)?;
    let update = pending
        .take_if_current(channel, generation)
        .ok_or_else(|| {
            "No pending update for the selected channel. Check for updates first.".to_string()
        })?;

    let emitter = app.clone();
    let mut downloaded_bytes: u64 = 0;

    // Download only (no install) so the channel can be re-verified after the
    // bytes arrive and before anything touches the installation.
    let bytes = update
        .download(
            move |chunk, total| {
                downloaded_bytes += chunk as u64;
                let _ = emitter.emit(
                    "update-download-progress",
                    DownloadProgress {
                        downloaded_bytes,
                        total_bytes: total.unwrap_or(0),
                    },
                );
            },
            || {
                // Download finished; verification and install follow.
            },
        )
        .await
        .map_err(|e| e.to_string())?;

    // A channel switch during the in-flight download must never result in the
    // previous channel's update being installed: re-verify the selection epoch
    // after the await, before installing.
    let (current_channel, current_generation) = current_selection(&container, &pending)?;
    if current_channel != channel || current_generation != generation {
        log::warn!(
            "[UpdateManager] Downloaded {channel:?} update discarded: channel switched during download"
        );
        return Err(
            "Update channel changed during download; the download was discarded.".to_string(),
        );
    }

    update.install(bytes).map_err(|e| e.to_string())?;
    Ok(())
}

/// Explicit invalidation (e.g. the frontend resetting after a channel change):
/// bumps the selection generation and discards any pending update.
#[tauri::command]
pub fn clear_pending_update(pending: State<'_, PendingUpdate>) -> Result<(), String> {
    pending.invalidate();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(s: &str) -> semver::Version {
        semver::Version::parse(s).expect("valid semver")
    }

    fn offers(current: &str, candidate: &str) -> bool {
        should_offer_update(&v(current), &v(candidate))
    }

    #[test]
    fn update_channel_urls_are_valid_https() {
        for channel in [UpdateChannel::Beta, UpdateChannel::Development] {
            let url: url::Url = channel.manifest_url().parse().unwrap();
            assert_eq!(url.scheme(), "https");
            assert!(url.path().ends_with("latest.json"));
        }
    }

    // ── Channel-aware version comparison ──

    #[test]
    fn development_line_accepts_strictly_newer_builds_only() {
        assert!(offers("0.1.0-dev.125", "0.1.0-dev.126"));
        assert!(!offers("0.1.0-dev.125", "0.1.0-dev.125"));
        assert!(!offers("0.1.0-dev.126", "0.1.0-dev.125"));
        // Build numbers compare numerically, not lexically.
        assert!(offers("0.1.0-dev.99", "0.1.0-dev.100"));
    }

    #[test]
    fn beta_line_accepts_strictly_newer_builds_only() {
        assert!(offers("0.1.0-beta.16", "0.1.0-beta.17"));
        assert!(!offers("0.1.0-beta.16", "0.1.0-beta.16"));
        assert!(!offers("0.1.0-beta.16", "0.1.0-beta.15"));
    }

    #[test]
    fn cross_channel_switches_are_always_offered() {
        // Switching to Development must work even though plain semver can
        // rank the two pre-release lines against each other.
        assert!(offers("0.1.0-beta.16", "0.1.0-dev.125"));
        // Switching to Beta must work when the installation is Development.
        assert!(offers("0.1.0-dev.125", "0.1.0-beta.16"));
    }

    #[test]
    fn stable_line_is_a_placeholder_that_never_downgrades_to_prereleases() {
        assert!(!offers("0.1.0", "0.1.0-beta.16"));
        assert!(!offers("0.1.0", "0.1.0-dev.125"));
        // ...while any pre-release may follow a stable promotion.
        assert!(offers("0.1.0-dev.125", "0.1.0"));
        assert!(offers("0.1.0-beta.16", "0.2.0"));
    }

    #[test]
    fn unrecognized_version_shapes_fall_back_to_plain_semver() {
        assert!(offers("0.1.0-nightly.5", "0.1.1"));
        assert!(!offers("0.1.1", "0.1.0-nightly.5"));
        assert!(!offers("0.1.1", "0.1.1"));
    }

    // ── Channel/generation binding ──

    #[test]
    fn ledger_stores_and_releases_within_one_generation() {
        let mut ledger = UpdateLedger::default();
        assert_eq!(ledger.generation, 0);
        assert!(ledger.store(UpdateChannel::Beta, 0));
        assert!(ledger.is_current(UpdateChannel::Beta, 0));
        assert!(ledger.take(UpdateChannel::Beta, 0));
        assert!(ledger.pending.is_none());
    }

    #[test]
    fn ledger_channel_switch_while_check_in_flight_discards_result() {
        // A check starts on Beta, capturing generation 0...
        let mut ledger = UpdateLedger::default();
        let check_channel = UpdateChannel::Beta;
        let check_generation = ledger.generation;
        // ...the user switches to Development before the check completes...
        assert_eq!(ledger.invalidate(), 1);
        // ...so the completed result for the old epoch must be refused.
        assert!(!ledger.store(check_channel, check_generation));
        assert!(ledger.pending.is_none());
    }

    #[test]
    fn ledger_channel_switch_after_check_before_download_discards_update() {
        let mut ledger = UpdateLedger::default();
        assert!(ledger.store(UpdateChannel::Beta, ledger.generation));
        // The channel switch invalidates the stored update...
        assert_eq!(ledger.invalidate(), 1);
        // ...so the download can neither confirm nor take the old binding.
        assert!(!ledger.is_current(UpdateChannel::Beta, 0));
        assert!(!ledger.take(UpdateChannel::Beta, 0));
        assert!(!ledger.take(UpdateChannel::Development, 1));
        assert!(ledger.pending.is_none());
    }

    #[test]
    fn ledger_channel_switch_during_download_blocks_install() {
        let mut ledger = UpdateLedger::default();
        assert!(ledger.store(UpdateChannel::Beta, 0));
        // The download takes the binding...
        assert!(ledger.take(UpdateChannel::Beta, 0));
        // ...and a switch happens while the bytes are streaming.
        assert_eq!(ledger.invalidate(), 1);
        // The post-download verification must fail for the old epoch.
        assert!(!ledger.is_current(UpdateChannel::Beta, 0));
    }

    #[test]
    fn ledger_rechecks_within_same_generation_replace_the_binding() {
        let mut ledger = UpdateLedger::default();
        assert!(ledger.store(UpdateChannel::Beta, 0));
        assert!(ledger.store(UpdateChannel::Beta, 0));
        assert!(ledger.take(UpdateChannel::Beta, 0));
    }

    #[test]
    fn ledger_generations_increase_monotonically() {
        let mut ledger = UpdateLedger::default();
        assert_eq!(ledger.invalidate(), 1);
        assert_eq!(ledger.invalidate(), 2);
        assert_eq!(ledger.invalidate(), 3);
        assert!(ledger.pending.is_none());
    }
}

/**
 * DropFlow — Updater Configuration Layer
 *
 * This module isolates all update provider metadata and release channel labels.
 * The native Tauri updater engine resolves endpoints directly through `tauri.conf.json`.
 */

export type ReleaseChannel = "beta" | "stable" | "nightly";

export interface UpdaterConfig {
  /** Target release channel */
  channel: ReleaseChannel;
  /** Provider display name */
  providerName: string;
  /** Default check timeout in milliseconds */
  timeoutMs: number;
}

export const UPDATER_CONFIG: UpdaterConfig = {
  channel: "beta",
  providerName: "GitHub Releases (Beta)",
  timeoutMs: 120000,
};

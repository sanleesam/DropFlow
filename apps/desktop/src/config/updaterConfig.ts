/**
 * DropFlow — Updater Configuration Layer
 *
 * This module isolates update provider metadata and the check timeout. The
 * update channel itself is user-selectable (Settings → About) and persisted in
 * the Rust settings; the channel-to-endpoint mapping lives in
 * `state_manager::UpdateChannel::manifest_url`.
 */

import type { UpdateChannel } from "../types/updater";

export interface UpdaterConfig {
  /** Channel the UI advertises as the default recommendation */
  recommendedChannel: UpdateChannel;
  /** Provider display name */
  providerName: string;
  /** Default check timeout in milliseconds */
  timeoutMs: number;
}

export const UPDATER_CONFIG: UpdaterConfig = {
  recommendedChannel: "beta",
  providerName: "GitHub Releases",
  timeoutMs: 120000,
};

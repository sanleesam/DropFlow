/**
 * DropFlow — Updater Configuration Layer
 *
 * This module isolates all update provider metadata, release channels, and endpoints.
 * Future update servers (e.g. self-hosted) can be configured here without modifying UI components.
 */

export type ReleaseChannel = "beta" | "stable" | "nightly";

export interface UpdaterConfig {
  /** Target release channel */
  channel: ReleaseChannel;
  /** Provider name (e.g., "GitHub Releases", "Self-Hosted") */
  providerName: string;
  /** Primary updater manifest endpoint URL */
  updateEndpoint: string;
  /** Optional fallback update manifest endpoint URL */
  fallbackEndpoint?: string;
  /** Default check timeout in milliseconds */
  timeoutMs: number;
}

export const GITHUB_RELEASES_BASE = "https://github.com/sanleesam/DropFlow/releases";

/**
 * Returns the appropriate manifest endpoint for a given release channel.
 */
export function getChannelEndpoint(channel: ReleaseChannel): string {
  switch (channel) {
    case "beta":
      return `${GITHUB_RELEASES_BASE}/latest/download/latest.json`;
    case "stable":
      return `${GITHUB_RELEASES_BASE}/latest/download/latest.json`;
    case "nightly":
      return `${GITHUB_RELEASES_BASE}/download/nightly/latest.json`;
  }
}

export const UPDATER_CONFIG: UpdaterConfig = {
  channel: "beta",
  providerName: "GitHub Releases (Beta)",
  updateEndpoint: getChannelEndpoint("beta"),
  timeoutMs: 15000,
};

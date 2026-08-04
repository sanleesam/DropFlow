/**
 * DropFlow — Updater State Model & Type Definitions
 */

export type UpdateStatus =
  | "idle"
  | "checking"
  | "no-update"
  | "update-available"
  | "downloading"
  | "installing"
  | "restart-required"
  | "error";

export interface UpdateVersionInfo {
  currentVersion: string;
  availableVersion?: string;
  releaseNotes?: string;
  pubDate?: string;
}

export interface ProgressData {
  downloadedBytes: number;
  totalBytes: number;
  percentage: number;
}

export interface UpdaterState {
  status: UpdateStatus;
  versionInfo: UpdateVersionInfo;
  progress?: ProgressData;
  lastCheckedAt?: number; // Epoch timestamp in ms
  error?: string;
}

export type UpdaterListener = (state: UpdaterState) => void;

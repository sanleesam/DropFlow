/**
 * DropFlow — UpdateService
 *
 * Dedicated service layer for application updates. Update checking, download
 * and installation run in Rust (`src-tauri/src/update_manager.rs`) so the
 * updater endpoint can be selected at runtime from the user's persisted update
 * channel — the JavaScript updater plugin cannot override endpoints per check.
 * Visual UI components never invoke update commands directly; they go through
 * this service and the `useUpdater` hook.
 */

import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { relaunch } from "@tauri-apps/plugin-process";
import { UPDATER_CONFIG } from "../config/updaterConfig";
import type {
  ProgressData,
  UpdateInfo,
  UpdaterState,
  UpdaterListener,
  UpdateVersionInfo,
} from "../types/updater";

class UpdateService {
  private static instance: UpdateService;
  private listeners: Set<UpdaterListener> = new Set();

  private state: UpdaterState = {
    status: "idle",
    versionInfo: {
      currentVersion: "0.1.0",
    },
  };

  private constructor() {}

  public static getInstance(): UpdateService {
    if (!UpdateService.instance) {
      UpdateService.instance = new UpdateService();
    }
    return UpdateService.instance;
  }

  public getState(): UpdaterState {
    return { ...this.state };
  }

  public subscribe(listener: UpdaterListener): () => void {
    this.listeners.add(listener);
    // Immediately emit current state upon subscription
    listener(this.getState());
    return () => {
      this.listeners.delete(listener);
    };
  }

  private setState(partialState: Partial<UpdaterState>): void {
    this.state = {
      ...this.state,
      ...partialState,
    };
    this.notifyListeners();
  }

  private notifyListeners(): void {
    const currentState = this.getState();
    this.listeners.forEach((listener) => {
      try {
        listener(currentState);
      } catch (err) {
        console.error("[UpdateService] Error in state listener:", err);
      }
    });
  }

  /**
   * Checks the user's selected update channel for a newer release. The Rust
   * command reads the persisted channel setting at check time, so a channel
   * switch takes effect immediately.
   */
  public async checkForUpdates(): Promise<void> {
    if (this.state.status === "checking" || this.state.status === "downloading") {
      return;
    }

    this.setState({
      status: "checking",
      error: undefined,
    });

    try {
      const info = await invoke<UpdateInfo>("check_for_updates", {
        timeoutMs: UPDATER_CONFIG.timeoutMs,
      });

      const now = Date.now();
      const currentVersion = info.currentVersion || this.state.versionInfo.currentVersion;

      if (info.available && info.availableVersion) {
        const versionInfo: UpdateVersionInfo = {
          currentVersion,
          availableVersion: info.availableVersion,
          releaseNotes: info.releaseNotes || undefined,
          pubDate: info.pubDate || undefined,
        };

        this.setState({
          status: "update-available",
          versionInfo,
          lastCheckedAt: now,
          error: undefined,
        });
      } else {
        this.setState({
          status: "no-update",
          versionInfo: { currentVersion },
          lastCheckedAt: now,
          error: undefined,
        });
      }
    } catch (error) {
      console.dir(error, { depth: null });

      const extractFullErrorChain = (err: unknown): string => {
        if (!err) return "Unknown error (null/undefined)";

        const parts: string[] = [];
        let current: any = err;
        let level = 0;
        const visited = new Set();

        while (current && level < 15 && !visited.has(current)) {
          visited.add(current);
          const prefix = level === 0 ? "" : `Caused by [${level}]: `;

          if (current instanceof Error) {
            parts.push(`${prefix}${current.name}: ${current.message}`);
            if (current.stack && level === 0) {
              parts.push(`Stack:\n${current.stack}`);
            }
            current = (current as any).cause;
          } else if (typeof current === "object") {
            const str = current.message || current.error || current.details || JSON.stringify(current, null, 2);
            parts.push(`${prefix}${str}`);
            current = current.cause || current.reason || current.source;
          } else {
            parts.push(`${prefix}${String(current)}`);
            break;
          }
          level++;
        }

        return parts.join("\n\n");
      };

      const fullDiagnosticError = extractFullErrorChain(error);
      console.error("[UpdateService] Full error chain:\n", fullDiagnosticError);

      this.setState({
        status: "error",
        error: fullDiagnosticError,
        lastCheckedAt: Date.now(),
      });
    }
  }

  /**
   * Downloads and installs the update announced by the last check. Download
   * progress arrives as `update-download-progress` events from the Rust side.
   */
  public async downloadUpdate(): Promise<void> {
    if (this.state.status === "downloading" || this.state.status === "installing") {
      return;
    }

    this.setState({
      status: "downloading",
      progress: {
        downloadedBytes: 0,
        totalBytes: 0,
        percentage: 0,
      },
      error: undefined,
    });

    let unlisten: UnlistenFn | undefined;
    try {
      unlisten = await listen<ProgressData>("update-download-progress", (event) => {
        const { downloadedBytes, totalBytes } = event.payload;
        const percentage =
          totalBytes > 0
            ? Math.min(100, Math.round((downloadedBytes / totalBytes) * 100))
            : 0;

        this.setState({
          status: "downloading",
          progress: { downloadedBytes, totalBytes, percentage },
        });
      });

      // Resolves once the update has been downloaded and installed. On Windows
      // the installer may exit the app instead of returning.
      await invoke("download_and_install_update");

      // Once download & install finish, prompt for restart
      this.setState({
        status: "restart-required",
        progress: undefined,
        error: undefined,
      });
    } catch (error) {
      console.error("[UpdateService] Download failed:", error);
      const errorMessage =
        error instanceof Error ? error.message : String(error);

      this.setState({
        status: "error",
        error: `Download failed: ${errorMessage}`,
        progress: undefined,
      });
    } finally {
      unlisten?.();
    }
  }

  /**
   * Restarts the application to apply the newly installed update.
   */
  public async restartApplication(): Promise<void> {
    try {
      await relaunch();
    } catch (error) {
      console.error("[UpdateService] Application relaunch failed:", error);
      this.setState({
        status: "error",
        error: `Failed to restart application: ${error}`,
      });
    }
  }

  /**
   * Drops any pending update and returns to the idle state. Called when the
   * user switches update channels so a stale cross-channel update can never be
   * downloaded (the Rust-side pending update is cleared as well).
   */
  public async reset(): Promise<void> {
    try {
      await invoke("clear_pending_update");
    } catch (error) {
      console.warn("[UpdateService] Failed to clear pending update:", error);
    }
    this.setState({ status: "idle", error: undefined, progress: undefined });
  }
}

export const updateService = UpdateService.getInstance();

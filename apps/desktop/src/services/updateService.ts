/**
 * DropFlow — UpdateService
 *
 * Dedicated service layer encapsulating Tauri v2 updater APIs (`@tauri-apps/plugin-updater`
 * and `@tauri-apps/plugin-process`). Visual UI components never invoke Tauri updater APIs directly.
 */

import { check, type Update } from "@tauri-apps/plugin-updater";
import { relaunch } from "@tauri-apps/plugin-process";
import { UPDATER_CONFIG } from "../config/updaterConfig";
import type {
  UpdaterState,
  UpdaterListener,
  UpdateVersionInfo,
} from "../types/updater";

class UpdateService {
  private static instance: UpdateService;
  private pendingUpdate: Update | null = null;
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
   * Checks for available application updates using Tauri v2 updater plugin.
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
      const update = await check({
        headers: {
          "X-DropFlow-Channel": UPDATER_CONFIG.channel,
        },
        timeout: UPDATER_CONFIG.timeoutMs,
      });

      const now = Date.now();

      if (update && update.available) {
        this.pendingUpdate = update;
        const versionInfo: UpdateVersionInfo = {
          currentVersion: update.currentVersion || this.state.versionInfo.currentVersion,
          availableVersion: update.version,
          releaseNotes: update.body || undefined,
          pubDate: update.date || undefined,
        };

        this.setState({
          status: "update-available",
          versionInfo,
          lastCheckedAt: now,
          error: undefined,
        });
      } else {
        this.pendingUpdate = null;
        this.setState({
          status: "no-update",
          versionInfo: {
            currentVersion: update?.currentVersion || this.state.versionInfo.currentVersion,
          },
          lastCheckedAt: now,
          error: undefined,
        });
      }
    } catch (error) {
      console.error("[UpdateService] Check for updates failed:", error);
      const rawError = error instanceof Error ? error.message : String(error);
      let userFriendlyError = rawError;

      if (rawError.includes("404") || rawError.includes("NotFound")) {
        userFriendlyError = "No release manifest found at update endpoint (404).";
      } else if (rawError.includes("signature") || rawError.includes("minisign")) {
        userFriendlyError = "Update signature verification failed.";
      } else if (rawError.includes("Could not fetch") || rawError.includes("network") || rawError.includes("dns")) {
        userFriendlyError = "Unable to reach update server. Check network connection.";
      }

      this.setState({
        status: "error",
        error: userFriendlyError,
        lastCheckedAt: Date.now(),
      });
    }
  }

  /**
   * Downloads and installs the pending update with real-time progress callbacks.
   */
  public async downloadUpdate(): Promise<void> {
    if (!this.pendingUpdate || this.state.status === "downloading") {
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

    try {
      let downloadedBytes = 0;
      let totalBytes = 0;

      await this.pendingUpdate.downloadAndInstall((event) => {
        switch (event.event) {
          case "Started":
            totalBytes = event.data.contentLength || 0;
            this.setState({
              status: "downloading",
              progress: {
                downloadedBytes: 0,
                totalBytes,
                percentage: 0,
              },
            });
            break;

          case "Progress":
            downloadedBytes += event.data.chunkLength;
            const percentage =
              totalBytes > 0
                ? Math.min(100, Math.round((downloadedBytes / totalBytes) * 100))
                : 0;

            this.setState({
              status: "downloading",
              progress: {
                downloadedBytes,
                totalBytes,
                percentage,
              },
            });
            break;

          case "Finished":
            this.setState({
              status: "installing",
              progress: {
                downloadedBytes,
                totalBytes,
                percentage: 100,
              },
            });
            break;
        }
      });

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
}

export const updateService = UpdateService.getInstance();

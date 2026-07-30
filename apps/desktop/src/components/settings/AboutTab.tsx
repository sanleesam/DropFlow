import React, { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { useSettings, ACCENT_COLOR_MAPS } from "../SettingsProvider";
import { useUpdater } from "../../hooks/useUpdater";
import { UPDATER_CONFIG } from "../../config/updaterConfig";

interface ReleaseInfo {
  version: string;
  display_version: string;
  release_tag: string;
  channel: string;
}

export const AboutTab: React.FC = () => {
  const { settings } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];
  const { state, checkForUpdates, downloadUpdate, restartApplication } = useUpdater();
  const [releaseInfo, setReleaseInfo] = useState<ReleaseInfo | null>(null);

  useEffect(() => {
    invoke<ReleaseInfo>("get_release_info")
      .then((info) => setReleaseInfo(info))
      .catch((err) => console.warn("[AboutTab] Failed to fetch release info:", err));
  }, []);

  const getPlatformName = () => {
    if (typeof window !== "undefined" && window.navigator) {
      const userAgent = navigator.userAgent || "";
      const platform = navigator.platform || "";
      if (/mac/i.test(userAgent) || /mac/i.test(platform)) return "macOS (Tauri)";
      if (/win/i.test(userAgent) || /win/i.test(platform)) return "Windows (Tauri)";
      if (/linux/i.test(userAgent) || /linux/i.test(platform)) return "Linux (Tauri)";
    }
    return "Desktop (Tauri)";
  };

  const formatBytes = (bytes: number): string => {
    if (bytes === 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + " " + sizes[i];
  };

  const formatLastChecked = (timestamp?: number): string => {
    if (!timestamp) return "Never";
    const diffMs = Date.now() - timestamp;
    const diffSecs = Math.floor(diffMs / 1000);
    if (diffSecs < 30) return "Just now";
    if (diffSecs < 60) return "Less than a minute ago";
    const diffMins = Math.floor(diffSecs / 60);
    if (diffMins < 60) return `${diffMins} min${diffMins > 1 ? "s" : ""} ago`;
    const diffHours = Math.floor(diffMins / 60);
    if (diffHours < 24) return `${diffHours} hour${diffHours > 1 ? "s" : ""} ago`;
    return new Date(timestamp).toLocaleDateString();
  };

  const isBusy = state.status === "checking" || state.status === "downloading" || state.status === "installing";
  const displayVersion = releaseInfo?.display_version || state.versionInfo.currentVersion;
  const channelDisplay = releaseInfo?.channel || UPDATER_CONFIG.channel;

  return (
    <div className="flex flex-col gap-5 animate-[backdrop-fade-in_0.15s_ease-out]">
      <div>
        <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-400 mb-4 select-none">
          About DropFlow
        </h3>

        {/* Version & Environment Specs Grid */}
        <div className="flex flex-col gap-3.5 pb-4 border-b border-white/[0.07] select-none">
          <div className="grid grid-cols-2 gap-3 text-xs bg-neutral-950/40 border border-white/[0.08] rounded-xl p-3.5">
            <div className="flex flex-col gap-0.5">
              <span className="text-[10px] text-neutral-500 font-semibold uppercase tracking-wider">DropFlow</span>
              <span className="text-neutral-200 font-medium">v{displayVersion}</span>
            </div>
            <div className="flex flex-col gap-0.5">
              <span className="text-[10px] text-neutral-500 font-semibold uppercase tracking-wider">Channel</span>
              <span className="text-neutral-200 font-medium capitalize">{channelDisplay}</span>
            </div>
            <div className="flex flex-col gap-0.5">
              <span className="text-[10px] text-neutral-500 font-semibold uppercase tracking-wider">Tauri</span>
              <span className="text-neutral-200 font-medium">v2.0.0</span>
            </div>
            <div className="flex flex-col gap-0.5">
              <span className="text-[10px] text-neutral-500 font-semibold uppercase tracking-wider">Platform</span>
              <span className="text-neutral-200 font-medium">{getPlatformName()}</span>
            </div>
          </div>
        </div>

        {/* Software Updates Section */}
        <div className="pt-4 flex flex-col gap-3">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-neutral-400 select-none">
            Software Updates
          </h4>

          <div className="bg-neutral-950/30 border border-white/[0.08] rounded-xl p-4 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <div className="flex flex-col gap-0.5">
                <span className="text-xs font-semibold text-neutral-200">
                  {state.status === "update-available" && `Version v${state.versionInfo.availableVersion} Available`}
                  {state.status === "restart-required" && "Update Ready to Install"}
                  {state.status === "no-update" && "DropFlow is Up to Date"}
                  {state.status === "checking" && "Checking for Updates..."}
                  {state.status === "downloading" && "Downloading Update..."}
                  {state.status === "installing" && "Installing Update..."}
                  {state.status === "idle" && "Automatic Updates"}
                  {state.status === "error" && "Update Check Failed"}
                </span>
                <span className="text-[11px] text-neutral-500">
                  Last checked: {formatLastChecked(state.lastCheckedAt)} · Provider: {UPDATER_CONFIG.providerName}
                </span>
              </div>

              {/* Status Badge */}
              <div className="shrink-0">
                {state.status === "no-update" && (
                  <span className="px-2 py-0.5 rounded-md text-[11px] font-medium bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                    Up to date
                  </span>
                )}
                {state.status === "update-available" && (
                  <span className="px-2 py-0.5 rounded-md text-[11px] font-medium bg-amber-500/10 text-amber-400 border border-amber-500/20">
                    v{state.versionInfo.availableVersion} available
                  </span>
                )}
                {state.status === "restart-required" && (
                  <span className="px-2 py-0.5 rounded-md text-[11px] font-medium bg-blue-500/10 text-blue-400 border border-blue-500/20">
                    Restart required
                  </span>
                )}
              </div>
            </div>

            {/* Download Progress Bar */}
            {(state.status === "downloading" || state.status === "installing") && state.progress && (
              <div className="flex flex-col gap-1.5 pt-1">
                <div className="flex items-center justify-between text-[11px] text-neutral-400">
                  <span>{state.status === "installing" ? "Installing..." : `Downloading (${state.progress.percentage}%)`}</span>
                  <span>{formatBytes(state.progress.downloadedBytes)} / {formatBytes(state.progress.totalBytes)}</span>
                </div>
                <div className="w-full h-1.5 bg-neutral-800 rounded-full overflow-hidden">
                  <div
                    className={`h-full ${accent.switchBg} transition-all duration-200 ease-out`}
                    style={{ width: `${state.progress.percentage}%` }}
                  />
                </div>
              </div>
            )}

            {/* Error Message */}
            {state.status === "error" && state.error && (
              <div className="text-[11px] text-red-400 bg-red-500/10 border border-red-500/20 rounded-lg p-2.5">
                {state.error}
              </div>
            )}

            {/* Release Notes */}
            {state.versionInfo.releaseNotes && state.status === "update-available" && (
              <div className="text-[11px] text-neutral-400 bg-neutral-900/60 rounded-lg p-2.5 max-h-24 overflow-y-auto border border-white/[0.04]">
                <span className="font-semibold text-neutral-300 block mb-1">Release Notes:</span>
                <p className="whitespace-pre-line text-neutral-400 leading-relaxed">
                  {state.versionInfo.releaseNotes}
                </p>
              </div>
            )}

            {/* Action Buttons */}
            <div className="flex items-center justify-end gap-2 pt-1 border-t border-white/[0.05]">
              {state.status === "update-available" ? (
                <button
                  type="button"
                  onClick={downloadUpdate}
                  disabled={isBusy}
                  className={`px-3 py-1.5 rounded-lg text-xs font-semibold text-white transition-opacity duration-150 ${accent.switchBg} hover:opacity-90 cursor-pointer shadow-sm`}
                >
                  Download Update
                </button>
              ) : state.status === "restart-required" ? (
                <button
                  type="button"
                  onClick={restartApplication}
                  className={`px-3 py-1.5 rounded-lg text-xs font-semibold text-white transition-opacity duration-150 ${accent.switchBg} hover:opacity-90 cursor-pointer shadow-sm`}
                >
                  Restart & Install
                </button>
              ) : (
                <button
                  type="button"
                  onClick={checkForUpdates}
                  disabled={isBusy}
                  className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors duration-150 border border-white/[0.08] ${
                    isBusy
                      ? "bg-neutral-800 text-neutral-500 cursor-not-allowed"
                      : "bg-neutral-800 hover:bg-neutral-700 text-neutral-200 cursor-pointer"
                  }`}
                >
                  {state.status === "checking" ? "Checking..." : "Check for Updates"}
                </button>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Footer Credits */}
      <div className="mt-4 flex items-center justify-center text-xs text-neutral-600 select-none">
        <span>DropFlow · Local-first file transfer utility</span>
      </div>
    </div>
  );
};

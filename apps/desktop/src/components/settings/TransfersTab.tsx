import React, { useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { invoke } from "@tauri-apps/api/core";
import { useSettings, ACCENT_COLOR_MAPS } from "../SettingsProvider";
import { Switch } from "../SettingsModal";

interface TransfersTabProps {
  historyCount?: number;
  onClearHistory?: () => void;
}

export const TransfersTab: React.FC<TransfersTabProps> = ({ historyCount = 0, onClearHistory }) => {
  const { settings, updateSetting } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];
  const [showConfirmModal, setShowConfirmModal] = useState(false);

  const hasHistory = historyCount > 0;

  const handleBrowseFolder = async () => {
    try {
      const selected = await open({
        directory: true,
        multiple: false,
        defaultPath: settings.receiveDirectory || undefined,
      });
      if (selected && typeof selected === "string") {
        updateSetting("receiveDirectory", selected);
      }
    } catch (e) {
      console.error("[TransfersTab] Error selecting download directory:", e);
    }
  };

  const handleExportHistory = async () => {
    try {
      const appState = await invoke<any>("get_app_state");
      const historyJson = JSON.stringify(appState?.history || [], null, 2);
      const blob = new Blob([historyJson], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `dropflow-history-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      console.error("[TransfersTab] Failed to export transfer history:", e);
    }
  };

  const handleClearHistory = async () => {
    try {
      await invoke("clear_history");
      setShowConfirmModal(false);
      onClearHistory?.();
    } catch (e) {
      console.error("[TransfersTab] Failed to clear transfer history:", e);
    }
  };

  return (
    <div className="flex flex-col gap-5 animate-[backdrop-fade-in_0.15s_ease-out]">
      <div>
        <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-400 mb-4 select-none">
          Transfers Settings
        </h3>

        {/* File Download Folder Option */}
        <div className="flex flex-col gap-2 pb-4 border-b border-white/[0.07]">
          <div className="flex flex-col">
            <label className="text-xs font-semibold text-neutral-200">Download folder</label>
            <span className="text-xs text-neutral-500 mt-0.5">
              Where files received from other devices will be saved.
            </span>
          </div>
          <div className="flex items-center gap-2 mt-1.5">
            <div className="flex-1 text-xs font-mono text-neutral-300 bg-neutral-950/60 border border-white/[0.08] rounded-lg px-3 py-2 truncate">
              {settings.receiveDirectory || "~/Downloads/DropFlow"}
            </div>
            <button
              type="button"
              onClick={handleBrowseFolder}
              className="
                px-3 py-2 rounded-lg text-xs font-medium
                bg-neutral-800 hover:bg-neutral-700 text-neutral-200 border border-white/[0.06]
                transition-colors duration-150 cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-blue-500
              "
            >
              Change...
            </button>
          </div>
        </div>

        {/* Ask Before Overwrite Switch */}
        <div className="flex items-center justify-between py-3.5 border-b border-white/[0.07]">
          <div className="flex flex-col pr-4">
            <span className="text-xs font-semibold text-neutral-200">Ask before overwrite</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              Prompt for confirmation if a file with the same name already exists.
            </span>
          </div>
          <Switch checked={settings.askBeforeOverwrite} onChange={(val) => updateSetting("askBeforeOverwrite", val)} label="Ask before overwrite" />
        </div>

        {/* Auto-open Switch */}
        <div className="flex items-center justify-between py-3.5 border-b border-white/[0.07]">
          <div className="flex flex-col pr-4">
            <span className="text-xs font-semibold text-neutral-200">Auto-open completed transfers</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              Open the folder or file automatically when the transfer finishes.
            </span>
          </div>
          <Switch checked={settings.autoOpenCompleted} onChange={(val) => updateSetting("autoOpenCompleted", val)} label="Auto-open completed transfers" />
        </div>

        {/* Concurrent Transfers count selector */}
        <div className="flex items-center justify-between py-3.5">
          <div className="flex flex-col pr-4">
            <span className="text-xs font-semibold text-neutral-200">Concurrent transfers</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              Limit the number of active transfers allowed at the same time.
            </span>
          </div>
          <div className="flex items-center gap-1 bg-neutral-950/60 border border-white/[0.08] rounded-lg p-1 select-none">
            {[2, 3, 5].map((val) => {
              const isActive = settings.maxConcurrentTransfers === val;
              return (
                <button
                  key={val}
                  type="button"
                  onClick={() => updateSetting("maxConcurrentTransfers", val)}
                  className={`
                    px-2.5 py-1 text-xs font-medium rounded transition-colors duration-150 cursor-pointer
                    ${isActive ? `${accent.switchBg} text-white` : "text-neutral-400 hover:text-neutral-200"}
                  `}
                >
                  {val}
                </button>
              );
            })}
          </div>
        </div>

        {/* ── Data Section ── */}
        <div className="pt-5 border-t border-white/[0.07]">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-400 mb-4 select-none">
            Data
          </h3>

          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 p-4 rounded-xl border border-white/[0.07] bg-neutral-950/40 select-none">
            <div className="flex flex-col pr-2">
              <span className="text-xs font-semibold text-neutral-200">Transfer history</span>
              <span className="text-xs text-neutral-400 mt-0.5">
                Export or erase saved transfer history from this device.
              </span>
              <span className="text-[11px] text-neutral-500 mt-1">
                This does not delete transferred files or modify your settings.
              </span>
            </div>

            <div className="flex items-center gap-2 shrink-0">
              {hasHistory && (
                <button
                  type="button"
                  onClick={handleExportHistory}
                  className="
                    px-3 py-1.5 rounded-lg text-xs font-medium text-neutral-200 bg-neutral-800 border border-white/[0.08]
                    hover:bg-neutral-700 transition-colors duration-150 cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-blue-500
                  "
                >
                  Export JSON
                </button>
              )}

              {hasHistory ? (
                <button
                  type="button"
                  onClick={() => setShowConfirmModal(true)}
                  className="
                    px-3.5 py-1.5 rounded-lg text-xs font-medium text-red-400 bg-red-500/10 border border-red-500/20
                    hover:bg-red-500/20 hover:text-red-300 transition-colors duration-150 cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-red-500
                  "
                >
                  Clear history
                </button>
              ) : (
                <span className="text-xs text-neutral-500 italic">
                  No transfer history.
                </span>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Confirmation Modal */}
      {showConfirmModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4 backdrop-blur-sm animate-backdrop-fade-in">
          <div className="flex flex-col w-full max-w-md rounded-xl border border-white/[0.1] bg-[#16181d] p-5 shadow-2xl animate-modal-scale-in select-none">
            <h3 className="text-sm font-semibold text-neutral-100">Clear transfer history?</h3>
            <p className="text-xs text-neutral-400 mt-2 leading-relaxed">
              This will permanently remove all saved transfer history from this device. Your transferred files, settings, receive directory, and device identity will not be affected.
            </p>
            <div className="flex items-center justify-end gap-2.5 mt-5">
              <button
                type="button"
                onClick={() => setShowConfirmModal(false)}
                className="px-3.5 py-1.5 rounded-lg border border-white/[0.08] bg-neutral-800 text-xs font-medium text-neutral-300 hover:bg-neutral-700 transition-colors"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleClearHistory}
                className="px-3.5 py-1.5 rounded-lg bg-red-600 hover:bg-red-500 text-xs font-medium text-white shadow-sm transition-colors"
              >
                Clear history
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

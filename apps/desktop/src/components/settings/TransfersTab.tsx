import React, { useState } from "react";
import { useSettings, ACCENT_COLOR_MAPS } from "../SettingsProvider";
import { Switch } from "../SettingsModal";

export const TransfersTab: React.FC = () => {
  const { settings, updateSetting } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];
  const [concurrentCount, setConcurrentCount] = useState(3);
  const [downloadPath, setDownloadPath] = useState("/Users/sanleesam/Downloads");

  const handleBrowseFolder = () => {
    // In a real tauri app we would invoke dialog.open.
    // Here we'll simulate toggle paths to prove interactivity.
    const folders = [
      "/Users/sanleesam/Downloads",
      "/Users/sanleesam/Downloads/DropFlow",
      "/Users/sanleesam/Desktop/DropFlow-Received"
    ];
    const currentIndex = folders.indexOf(downloadPath);
    const nextIndex = (currentIndex + 1) % folders.length;
    setDownloadPath(folders[nextIndex]);
  };

  return (
    <div className="flex flex-col gap-6 animate-[toast-slide-in_0.2s_ease-out]">
      <div>
        <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-4 select-none">
          Transfers Settings
        </h3>

        {/* File Download Folder Option */}
        <div className="flex flex-col gap-2 pb-5 border-b border-white/5">
          <div className="flex flex-col">
            <label className="text-sm font-semibold text-slate-200">Download folder</label>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              Where files received from other devices will be saved.
            </span>
          </div>
          <div className="flex items-center gap-2 mt-2">
            <div className="flex-1 text-xs font-mono text-slate-400 bg-slate-950/40 border border-white/5 rounded-xl px-3.5 py-2.5 truncate">
              {downloadPath}
            </div>
            <button
              type="button"
              onClick={handleBrowseFolder}
              className={`
                px-4 py-2.5 rounded-xl text-xs font-semibold
                bg-white/5 hover:bg-white/10 text-slate-300 hover:text-white border border-white/5
                transition-all duration-150 cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-[var(--df-accent)]
              `}
            >
              Change...
            </button>
          </div>
        </div>

        {/* Ask Before Overwrite Switch */}
        <div className="flex items-center justify-between py-4.5 border-b border-white/5">
          <div className="flex flex-col pr-4">
            <span className="text-sm font-semibold text-slate-200">Ask before overwrite</span>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              Prompt for confirmation if a file with the same name already exists.
            </span>
          </div>
          <Switch checked={settings.askBeforeOverwrite} onChange={(val) => updateSetting("askBeforeOverwrite", val)} label="Ask before overwrite" />
        </div>

        {/* Auto-open Switch */}
        <div className="flex items-center justify-between py-4.5 border-b border-white/5">
          <div className="flex flex-col pr-4">
            <span className="text-sm font-semibold text-slate-200">Auto-open completed transfers</span>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              Open the folder or file automatically when the transfer finishes.
            </span>
          </div>
          <Switch checked={settings.autoOpenCompleted} onChange={(val) => updateSetting("autoOpenCompleted", val)} label="Auto-open completed transfers" />
        </div>

        {/* Concurrent Transfers count selector */}
        <div className="flex items-center justify-between py-4.5">
          <div className="flex flex-col pr-4">
            <span className="text-sm font-semibold text-slate-200">Concurrent transfers</span>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              Limit the number of active transfers allowed at the same time.
            </span>
          </div>
          <div className="flex items-center gap-1 bg-slate-950/40 border border-white/5 rounded-xl p-1 select-none">
            {[2, 3, 5].map((val) => {
              const isActive = concurrentCount === val;
              return (
                <button
                  key={val}
                  type="button"
                  onClick={() => setConcurrentCount(val)}
                  className={`
                    px-3 py-1 text-xs font-semibold rounded-lg transition-all duration-150 cursor-pointer
                    ${isActive ? `${accent.switchBg} text-white` : "text-slate-400 hover:text-slate-200"}
                  `}
                >
                  {val}
                </button>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
};

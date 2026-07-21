import React, { useState } from "react";
import { useSettings, ACCENT_COLOR_MAPS } from "../SettingsProvider";
import { Switch } from "../SettingsModal";

export const TransfersTab: React.FC = () => {
  const { settings, updateSetting } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];
  const [concurrentCount, setConcurrentCount] = useState(3);
  const [downloadPath, setDownloadPath] = useState("~/Downloads");

  const handleBrowseFolder = () => {
    const folders = [
      "~/Downloads",
      "~/Downloads/DropFlow",
      "~/Desktop/Received"
    ];
    const currentIndex = folders.indexOf(downloadPath);
    const nextIndex = (currentIndex + 1) % folders.length;
    setDownloadPath(folders[nextIndex]);
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
              {downloadPath}
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
              const isActive = concurrentCount === val;
              return (
                <button
                  key={val}
                  type="button"
                  onClick={() => setConcurrentCount(val)}
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
      </div>
    </div>
  );
};

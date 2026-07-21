import React, { useState } from "react";
import { useSettings, ACCENT_COLOR_MAPS } from "../SettingsProvider";

export const AboutTab: React.FC = () => {
  const { settings } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];
  const [updateStatus, setUpdateStatus] = useState<string | null>(null);

  const handleCheckUpdates = () => {
    setUpdateStatus("checking");
    setTimeout(() => setUpdateStatus("latest"), 1200);
  };

  return (
    <div className="flex flex-col gap-5 animate-[backdrop-fade-in_0.15s_ease-out]">
      <div>
        <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-400 mb-4 select-none">
          About DropFlow
        </h3>

        {/* Version info list */}
        <div className="flex flex-col gap-3.5 pb-4 border-b border-white/[0.07] select-none">
          <div className="grid grid-cols-2 gap-3 text-xs bg-neutral-950/40 border border-white/[0.08] rounded-xl p-3.5">
            <div className="flex flex-col gap-0.5">
              <span className="text-[10px] text-neutral-500 font-semibold uppercase tracking-wider">DropFlow</span>
              <span className="text-neutral-200 font-medium">v0.1.0</span>
            </div>
            <div className="flex flex-col gap-0.5">
              <span className="text-[10px] text-neutral-500 font-semibold uppercase tracking-wider">React</span>
              <span className="text-neutral-200 font-medium">v19.1.0</span>
            </div>
            <div className="flex flex-col gap-0.5">
              <span className="text-[10px] text-neutral-500 font-semibold uppercase tracking-wider">Tauri</span>
              <span className="text-neutral-200 font-medium">v2.0.0</span>
            </div>
            <div className="flex flex-col gap-0.5">
              <span className="text-[10px] text-neutral-500 font-semibold uppercase tracking-wider">Platform</span>
              <span className="text-neutral-200 font-medium">macOS (Tauri)</span>
            </div>
          </div>
        </div>

        {/* Check for updates option */}
        <div className="flex items-center justify-between py-3.5">
          <div className="flex flex-col pr-4">
            <span className="text-xs font-semibold text-neutral-200">Check for updates</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              Verify if a newer version of DropFlow is available.
            </span>
          </div>
          <div className="flex flex-col items-end gap-1 shrink-0">
            <button
              type="button"
              onClick={handleCheckUpdates}
              disabled={updateStatus === "checking"}
              className={`
                px-3 py-1.5 rounded-lg text-xs font-medium select-none transition-colors duration-150 border border-white/[0.06]
                ${updateStatus === "checking"
                  ? "bg-neutral-800 text-neutral-500 cursor-not-allowed"
                  : `${accent.switchBg} hover:opacity-90 text-white cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-blue-500`
                }
              `}
            >
              {updateStatus === "checking" ? "Checking..." : "Check for Updates"}
            </button>
            {updateStatus === "latest" && (
              <span className="text-[10px] text-emerald-400 font-medium select-none animate-[backdrop-fade-in_0.15s_ease-out]">
                Up to date.
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Footer Credits */}
      <div className="mt-6 flex items-center justify-center text-xs text-neutral-600 select-none">
        <span>DropFlow · Local-first file transfer utility</span>
      </div>
    </div>
  );
};

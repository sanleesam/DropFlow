import React, { useState } from "react";
import { Heart } from "lucide-react";
import { useSettings, ACCENT_COLOR_MAPS } from "../SettingsProvider";

export const AboutTab: React.FC = () => {
  const { settings } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];
  const [updateStatus, setUpdateStatus] = useState<string | null>(null);

  const handleCheckUpdates = () => {
    setUpdateStatus("checking");
    setTimeout(() => setUpdateStatus("latest"), 1500);
  };

  return (
    <div className="flex flex-col gap-6 animate-[toast-slide-in_0.2s_ease-out]">
      <div>
        <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-4 select-none">
          About DropFlow
        </h3>

        {/* Version info list */}
        <div className="flex flex-col gap-3.5 pb-5 border-b border-white/5 select-none">
          <div className="grid grid-cols-2 gap-4 text-sm bg-slate-950/20 border border-white/5 rounded-2xl p-4">
            <div className="flex flex-col gap-1">
              <span className="text-[10px] text-slate-500 font-semibold uppercase tracking-wider">DropFlow</span>
              <span className="text-slate-200 font-semibold">v0.1.0</span>
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-[10px] text-slate-500 font-semibold uppercase tracking-wider">React</span>
              <span className="text-slate-200 font-semibold">v19.1.0</span>
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-[10px] text-slate-500 font-semibold uppercase tracking-wider">Tauri</span>
              <span className="text-slate-200 font-semibold">v2.0.0</span>
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-[10px] text-slate-500 font-semibold uppercase tracking-wider">Platform</span>
              <span className="text-slate-200 font-semibold">macOS (Tauri)</span>
            </div>
          </div>
        </div>

        {/* Check for updates option */}
        <div className="flex items-center justify-between py-4.5">
          <div className="flex flex-col pr-4">
            <span className="text-sm font-semibold text-slate-200">Check for updates</span>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              Verify if a newer version of DropFlow is available.
            </span>
          </div>
          <div className="flex flex-col items-end gap-1.5 shrink-0">
            <button
              type="button"
              onClick={handleCheckUpdates}
              disabled={updateStatus === "checking"}
              className={`
                px-4 py-2.5 rounded-xl text-xs font-semibold select-none transition-all duration-150 border border-white/5
                ${updateStatus === "checking"
                  ? "bg-slate-800 text-slate-500 cursor-not-allowed"
                  : `${accent.switchBg} hover:bg-opacity-90 text-white cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-[var(--df-accent)]`
                }
              `}
            >
              {updateStatus === "checking" ? "Checking..." : "Check for Updates"}
            </button>
            {updateStatus === "latest" && (
              <span className="text-[10px] text-emerald-400 font-semibold select-none animate-[toast-slide-in_0.2s_ease-out]">
                Your app is up to date.
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Footer Credits */}
      <div className="mt-8 flex items-center justify-center gap-1.5 text-xs text-slate-600 select-none">
        <span>Made with</span>
        <Heart size={10} className="text-red-500/60 fill-current" />
        <span>locally-first</span>
      </div>
    </div>
  );
};

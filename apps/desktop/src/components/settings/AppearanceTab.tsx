import React from "react";
import { Check } from "lucide-react";
import { useSettings, AccentColor } from "../SettingsProvider";
import { Switch } from "../SettingsModal";

export const AppearanceTab: React.FC = () => {
  const { settings, updateSetting } = useSettings();

  return (
    <div className="flex flex-col gap-6 animate-[toast-slide-in_0.2s_ease-out]">
      <div>
        <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-4 select-none">
          Appearance
        </h3>

        {/* Dark Theme Switch */}
        <div className="flex items-center justify-between py-4.5 border-b border-white/5">
          <div className="flex flex-col pr-4">
            <span className="text-sm font-semibold text-slate-200">Dark theme</span>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              Switch between dark and light appearance modes.
            </span>
          </div>
          <Switch checked={settings.darkTheme} onChange={(val) => updateSetting("darkTheme", val)} label="Dark theme" />
        </div>

        {/* Accent Color selection */}
        <div className="flex flex-col gap-3 py-5 border-b border-white/5">
          <div className="flex flex-col">
            <span className="text-sm font-semibold text-slate-200">Accent color</span>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              Choose the primary highlight color for buttons, selections, and status signals.
            </span>
          </div>
          <div className="flex items-center gap-3.5 mt-2 select-none">
            {(["blue", "indigo", "purple", "pink", "emerald"] as const).map((color) => {
              const bgClasses: Record<AccentColor, string> = {
                blue: "bg-blue-500 hover:bg-blue-400 focus:ring-blue-500/50",
                indigo: "bg-indigo-500 hover:bg-indigo-400 focus:ring-indigo-500/50",
                purple: "bg-purple-500 hover:bg-purple-400 focus:ring-purple-500/50",
                pink: "bg-pink-500 hover:bg-pink-400 focus:ring-pink-500/50",
                emerald: "bg-emerald-500 hover:bg-emerald-400 focus:ring-emerald-500/50",
              };
              const isActive = settings.accentColor === color;
              return (
                <button
                  key={color}
                  type="button"
                  onClick={() => updateSetting("accentColor", color)}
                  className={`
                    relative w-8 h-8 rounded-full flex items-center justify-center border border-white/10
                    transition-all duration-200 cursor-pointer outline-none hover:scale-110 active:scale-95
                    focus:ring-2 focus:ring-offset-2 focus:ring-offset-slate-900
                    ${bgClasses[color]}
                    ${isActive ? "scale-105 shadow-md" : "scale-100"}
                  `}
                  aria-label={`Set ${color} accent color`}
                >
                  {isActive && (
                    <Check size={16} className="text-white animate-[badge-pop_0.2s_ease-out]" strokeWidth={3.5} />
                  )}
                </button>
              );
            })}
          </div>
        </div>

        {/* Reduce Animations Switch */}
        <div className="flex items-center justify-between py-4.5">
          <div className="flex flex-col pr-4">
            <span className="text-sm font-semibold text-slate-200">Reduce animations</span>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              Minimize interface motion, scale-ups, and panel transitions.
            </span>
          </div>
          <Switch checked={settings.reduceAnimations} onChange={(val) => updateSetting("reduceAnimations", val)} label="Reduce animations" />
        </div>
      </div>
    </div>
  );
};

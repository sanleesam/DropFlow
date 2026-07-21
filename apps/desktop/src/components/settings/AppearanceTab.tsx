import React from "react";
import { Check } from "lucide-react";
import { useSettings, AccentColor } from "../SettingsProvider";
import { Switch } from "../SettingsModal";

export const AppearanceTab: React.FC = () => {
  const { settings, updateSetting } = useSettings();

  return (
    <div className="flex flex-col gap-5 animate-[backdrop-fade-in_0.15s_ease-out]">
      <div>
        <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-400 mb-4 select-none">
          Appearance
        </h3>

        {/* Dark Theme Switch */}
        <div className="flex items-center justify-between py-3.5 border-b border-white/[0.07]">
          <div className="flex flex-col pr-4">
            <span className="text-xs font-semibold text-neutral-200">Dark theme</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              Switch between dark and light appearance modes.
            </span>
          </div>
          <Switch checked={settings.darkTheme} onChange={(val) => updateSetting("darkTheme", val)} label="Dark theme" />
        </div>

        {/* Accent Color selection */}
        <div className="flex flex-col gap-2.5 py-4 border-b border-white/[0.07]">
          <div className="flex flex-col">
            <span className="text-xs font-semibold text-neutral-200">Accent color</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              Choose the primary highlight color for actions, selections, and status signals.
            </span>
          </div>
          <div className="flex items-center gap-3 mt-1.5 select-none">
            {(["blue", "indigo", "purple", "pink", "emerald"] as const).map((color) => {
              const bgClasses: Record<AccentColor, string> = {
                blue: "bg-blue-600 hover:bg-blue-500",
                indigo: "bg-indigo-600 hover:bg-indigo-500",
                purple: "bg-purple-600 hover:bg-purple-500",
                pink: "bg-pink-600 hover:bg-pink-500",
                emerald: "bg-emerald-600 hover:bg-emerald-500",
              };
              const isActive = settings.accentColor === color;
              return (
                <button
                  key={color}
                  type="button"
                  onClick={() => updateSetting("accentColor", color)}
                  className={`
                    relative w-7 h-7 rounded-full flex items-center justify-center border border-white/10
                    transition-all duration-150 cursor-pointer outline-none
                    focus:ring-2 focus:ring-offset-2 focus:ring-offset-neutral-900 focus:ring-blue-500
                    ${bgClasses[color]}
                    ${isActive ? "ring-2 ring-offset-2 ring-offset-neutral-900 ring-white shadow-sm" : "opacity-80 hover:opacity-100"}
                  `}
                  aria-label={`Set ${color} accent color`}
                >
                  {isActive && (
                    <Check size={14} className="text-white" strokeWidth={3} />
                  )}
                </button>
              );
            })}
          </div>
        </div>

        {/* Reduce Animations Switch */}
        <div className="flex items-center justify-between py-3.5">
          <div className="flex flex-col pr-4">
            <span className="text-xs font-semibold text-neutral-200">Reduce animations</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              Minimize interface motion and scale transitions.
            </span>
          </div>
          <Switch checked={settings.reduceAnimations} onChange={(val) => updateSetting("reduceAnimations", val)} label="Reduce animations" />
        </div>
      </div>
    </div>
  );
};

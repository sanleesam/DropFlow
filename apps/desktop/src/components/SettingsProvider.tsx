import React, { createContext, useContext, useState, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";

// ─── Types & Interfaces ──────────────────────────────────────────────────────

export type AccentColor = "blue" | "indigo" | "purple" | "pink" | "emerald";

export interface Settings {
  accentColor: AccentColor;
  askBeforeOverwrite: boolean;
  autoOpenCompleted: boolean;
  deviceName: string;
  deviceNameMode: "auto" | "custom";
  deviceVisibility: boolean;
  requireConfirmation: boolean;
  darkTheme: boolean;
  reduceAnimations: boolean;
}

interface SettingsContextType {
  settings: Settings;
  updateSetting: <K extends keyof Settings>(key: K, value: Settings[K]) => void;
}

// ─── Accent Color Tailwind Styles Map ─────────────────────────────────────────

export interface AccentStyles {
  buttonGrad: string;
  sidebarActive: string;
  switchBg: string;
  switchFocus: string;
  progressFrom: string;
  progressTo: string;
  progressBgDot: string;
  progressText: string;
  progressIconBg: string;
  progressIconText: string;
  deviceSelected: string;
  deviceBadgeBg: string;
  deviceIconBg: string;
  deviceFocus: string;
  headerLogoGrad: string;
  dropZoneActive: string;
  dropZoneHover: string;
  dropZoneIcon: string;
  dropZoneTitle: string;
  dropZoneOverlay: string;
  buttonShadow: string;
  ringFocus: string;
}

export const ACCENT_COLOR_MAPS: Record<AccentColor, AccentStyles> = {
  blue: {
    buttonGrad: "bg-gradient-to-r from-blue-500 to-cyan-500 hover:from-blue-400 hover:to-cyan-400 hover:shadow-[0_0_20px_rgba(59,130,246,0.35)]",
    sidebarActive: "bg-blue-500/10 text-blue-400 border border-blue-500/15",
    switchBg: "bg-blue-500",
    switchFocus: "focus:ring-blue-500/50",
    progressFrom: "from-blue-500",
    progressTo: "to-cyan-500",
    progressBgDot: "bg-blue-400",
    progressText: "text-blue-300",
    progressIconBg: "bg-blue-500/10",
    progressIconText: "text-blue-400",
    deviceSelected: "bg-blue-500/10 border border-blue-500/60 shadow-[0_0_0_1px_rgba(59,130,246,0.3),0_0_20px_rgba(59,130,246,0.15)]",
    deviceBadgeBg: "bg-blue-500 shadow-blue-500/40",
    deviceIconBg: "bg-blue-500/20 text-blue-400",
    deviceFocus: "focus-visible:ring-blue-500/60",
    headerLogoGrad: "from-blue-500 to-cyan-600 shadow-blue-500/30",
    dropZoneActive: "scale-[1.01] border-blue-500/70 bg-blue-500/10 shadow-[0_0_0_4px_rgba(59,130,246,0.1)]",
    dropZoneHover: "border-white/[.14] bg-white/[.015] hover:border-blue-400/45 hover:bg-blue-500/[.035]",
    dropZoneIcon: "text-blue-400",
    dropZoneTitle: "text-blue-300",
    dropZoneOverlay: "pointer-events-none absolute inset-0 rounded-xl bg-gradient-to-br from-blue-500/10 to-transparent",
    buttonShadow: "shadow-[0_8px_22px_rgba(37,99,235,0.22)]",
    ringFocus: "focus-visible:ring-blue-500/60 focus-visible:ring-offset-2 focus-visible:ring-offset-[#080b12]",
  },
  indigo: {
    buttonGrad: "bg-gradient-to-r from-indigo-500 to-purple-500 hover:from-indigo-400 hover:to-purple-400 hover:shadow-[0_0_20px_rgba(99,102,241,0.35)]",
    sidebarActive: "bg-indigo-500/10 text-indigo-400 border border-indigo-500/15",
    switchBg: "bg-indigo-500",
    switchFocus: "focus:ring-indigo-500/50",
    progressFrom: "from-indigo-500",
    progressTo: "to-purple-500",
    progressBgDot: "bg-indigo-400",
    progressText: "text-indigo-300",
    progressIconBg: "bg-indigo-500/10",
    progressIconText: "text-indigo-400",
    deviceSelected: "bg-indigo-500/10 border border-indigo-500/60 shadow-[0_0_0_1px_rgba(99,102,241,0.3),0_0_20px_rgba(99,102,241,0.15)]",
    deviceBadgeBg: "bg-indigo-500 shadow-indigo-500/40",
    deviceIconBg: "bg-indigo-500/20 text-indigo-400",
    deviceFocus: "focus-visible:ring-indigo-500/60",
    headerLogoGrad: "from-indigo-500 to-purple-600 shadow-indigo-500/30",
    dropZoneActive: "scale-[1.01] border-indigo-500/70 bg-indigo-500/10 shadow-[0_0_0_4px_rgba(99,102,241,0.1)]",
    dropZoneHover: "border-white/[.14] bg-white/[.015] hover:border-indigo-400/45 hover:bg-indigo-500/[.035]",
    dropZoneIcon: "text-indigo-400",
    dropZoneTitle: "text-indigo-300",
    dropZoneOverlay: "pointer-events-none absolute inset-0 rounded-xl bg-gradient-to-br from-indigo-500/10 to-transparent",
    buttonShadow: "shadow-[0_8px_22px_rgba(79,70,229,0.22)]",
    ringFocus: "focus-visible:ring-indigo-500/60 focus-visible:ring-offset-2 focus-visible:ring-offset-[#080b12]",
  },
  purple: {
    buttonGrad: "bg-gradient-to-r from-purple-500 to-pink-500 hover:from-purple-400 hover:to-pink-400 hover:shadow-[0_0_20px_rgba(168,85,247,0.35)]",
    sidebarActive: "bg-purple-500/10 text-purple-400 border border-purple-500/15",
    switchBg: "bg-purple-500",
    switchFocus: "focus:ring-purple-500/50",
    progressFrom: "from-purple-500",
    progressTo: "to-pink-500",
    progressBgDot: "bg-purple-400",
    progressText: "text-purple-300",
    progressIconBg: "bg-purple-500/10",
    progressIconText: "text-purple-400",
    deviceSelected: "bg-purple-500/10 border border-purple-500/60 shadow-[0_0_0_1px_rgba(168,85,247,0.3),0_0_20px_rgba(168,85,247,0.15)]",
    deviceBadgeBg: "bg-purple-500 shadow-purple-500/40",
    deviceIconBg: "bg-purple-500/20 text-purple-400",
    deviceFocus: "focus-visible:ring-purple-500/60",
    headerLogoGrad: "from-purple-500 to-pink-600 shadow-purple-500/30",
    dropZoneActive: "scale-[1.01] border-purple-500/70 bg-purple-500/10 shadow-[0_0_0_4px_rgba(168,85,247,0.1)]",
    dropZoneHover: "border-white/[.14] bg-white/[.015] hover:border-purple-400/45 hover:bg-purple-500/[.035]",
    dropZoneIcon: "text-purple-400",
    dropZoneTitle: "text-purple-300",
    dropZoneOverlay: "pointer-events-none absolute inset-0 rounded-xl bg-gradient-to-br from-purple-500/10 to-transparent",
    buttonShadow: "shadow-[0_8px_22px_rgba(147,51,234,0.22)]",
    ringFocus: "focus-visible:ring-purple-500/60 focus-visible:ring-offset-2 focus-visible:ring-offset-[#080b12]",
  },
  pink: {
    buttonGrad: "bg-gradient-to-r from-pink-500 to-rose-500 hover:from-pink-400 hover:to-rose-400 hover:shadow-[0_0_20px_rgba(236,72,153,0.35)]",
    sidebarActive: "bg-pink-500/10 text-pink-400 border border-pink-500/15",
    switchBg: "bg-pink-500",
    switchFocus: "focus:ring-pink-500/50",
    progressFrom: "from-pink-500",
    progressTo: "to-rose-500",
    progressBgDot: "bg-pink-400",
    progressText: "text-pink-300",
    progressIconBg: "bg-pink-500/10",
    progressIconText: "text-pink-400",
    deviceSelected: "bg-pink-500/10 border border-pink-500/60 shadow-[0_0_0_1px_rgba(236,72,153,0.3),0_0_20px_rgba(236,72,153,0.15)]",
    deviceBadgeBg: "bg-pink-500 shadow-pink-500/40",
    deviceIconBg: "bg-pink-500/20 text-pink-400",
    deviceFocus: "focus-visible:ring-pink-500/60",
    headerLogoGrad: "from-pink-500 to-rose-600 shadow-pink-500/30",
    dropZoneActive: "scale-[1.01] border-pink-500/70 bg-pink-500/10 shadow-[0_0_0_4px_rgba(236,72,153,0.1)]",
    dropZoneHover: "border-white/[.14] bg-white/[.015] hover:border-pink-400/45 hover:bg-pink-500/[.035]",
    dropZoneIcon: "text-pink-400",
    dropZoneTitle: "text-pink-300",
    dropZoneOverlay: "pointer-events-none absolute inset-0 rounded-xl bg-gradient-to-br from-pink-500/10 to-transparent",
    buttonShadow: "shadow-[0_8px_22px_rgba(219,39,119,0.22)]",
    ringFocus: "focus-visible:ring-pink-500/60 focus-visible:ring-offset-2 focus-visible:ring-offset-[#080b12]",
  },
  emerald: {
    buttonGrad: "bg-gradient-to-r from-emerald-500 to-teal-500 hover:from-emerald-400 hover:to-teal-400 hover:shadow-[0_0_20px_rgba(16,185,129,0.35)]",
    sidebarActive: "bg-emerald-500/10 text-emerald-400 border border-emerald-500/15",
    switchBg: "bg-emerald-500",
    switchFocus: "focus:ring-emerald-500/50",
    progressFrom: "from-emerald-500",
    progressTo: "to-teal-500",
    progressBgDot: "bg-emerald-400",
    progressText: "text-emerald-300",
    progressIconBg: "bg-emerald-500/10",
    progressIconText: "text-emerald-400",
    deviceSelected: "bg-emerald-500/10 border border-emerald-500/60 shadow-[0_0_0_1px_rgba(16,185,129,0.3),0_0_20px_rgba(16,185,129,0.15)]",
    deviceBadgeBg: "bg-emerald-500 shadow-emerald-500/40",
    deviceIconBg: "bg-emerald-500/20 text-emerald-400",
    deviceFocus: "focus-visible:ring-emerald-500/60",
    headerLogoGrad: "from-emerald-500 to-teal-600 shadow-emerald-500/30",
    dropZoneActive: "scale-[1.01] border-emerald-500/70 bg-emerald-500/10 shadow-[0_0_0_4px_rgba(16,185,129,0.1)]",
    dropZoneHover: "border-white/[.14] bg-white/[.015] hover:border-emerald-400/45 hover:bg-emerald-500/[.035]",
    dropZoneIcon: "text-emerald-400",
    dropZoneTitle: "text-emerald-300",
    dropZoneOverlay: "pointer-events-none absolute inset-0 rounded-xl bg-gradient-to-br from-emerald-500/10 to-transparent",
    buttonShadow: "shadow-[0_8px_22px_rgba(5,150,105,0.22)]",
    ringFocus: "focus-visible:ring-emerald-500/60 focus-visible:ring-offset-2 focus-visible:ring-offset-[#080b12]",
  },
};

// ─── Settings Context ─────────────────────────────────────────────────────────

const SettingsContext = createContext<SettingsContextType | undefined>(undefined);

export const SettingsProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [settings, setSettings] = useState<Settings>({
    accentColor: "blue",
    askBeforeOverwrite: true,
    autoOpenCompleted: false,
    deviceName: "",
    deviceNameMode: "auto",
    deviceVisibility: true,
    requireConfirmation: true,
    darkTheme: true,
    reduceAnimations: false,
  });

  // Dynamically inject CSS variables onto the document root based on active accent color
  useEffect(() => {
    const colors: Record<AccentColor, { primary: string; hover: string; soft: string; shadow: string; from: string; to: string }> = {
      blue: { primary: "#3b82f6", hover: "#60a5fa", soft: "rgba(59, 130, 246, 0.12)", shadow: "rgba(37, 99, 235, 0.22)", from: "#3b82f6", to: "#06b6d4" },
      indigo: { primary: "#6366f1", hover: "#818cf8", soft: "rgba(99, 102, 241, 0.12)", shadow: "rgba(79, 70, 229, 0.22)", from: "#6366f1", to: "#a855f7" },
      purple: { primary: "#a855f7", hover: "#c084fc", soft: "rgba(168, 85, 247, 0.12)", shadow: "rgba(147, 51, 234, 0.22)", from: "#a855f7", to: "#ec4899" },
      pink: { primary: "#ec4899", hover: "#f472b6", soft: "rgba(236, 72, 153, 0.12)", shadow: "rgba(219, 39, 119, 0.22)", from: "#ec4899", to: "#f43f5e" },
      emerald: { primary: "#10b981", hover: "#34d399", soft: "rgba(16, 185, 129, 0.12)", shadow: "rgba(5, 150, 105, 0.22)", from: "#10b981", to: "#14b8a6" },
    };
    const active = colors[settings.accentColor];
    document.documentElement.style.setProperty("--df-accent", active.primary);
    document.documentElement.style.setProperty("--df-accent-hover", active.hover);
    document.documentElement.style.setProperty("--df-accent-soft", active.soft);
    document.documentElement.style.setProperty("--df-accent-shadow", active.shadow);
    document.documentElement.style.setProperty("--df-accent-from", active.from);
    document.documentElement.style.setProperty("--df-accent-to", active.to);
  }, [settings.accentColor]);

  useEffect(() => {
    if (settings.deviceNameMode === "auto") {
      invoke<string>("get_system_computer_name")
        .then((sysName) => {
          setSettings((prev) => {
            if (prev.deviceNameMode === "auto") {
              return {
                ...prev,
                deviceName: sysName,
              };
            }
            return prev;
          });
        })
        .catch(console.error);
    }
  }, [settings.deviceNameMode]);

  const updateSetting = <K extends keyof Settings>(key: K, value: Settings[K]) => {
    setSettings((prev) => ({
      ...prev,
      [key]: value,
    }));
  };

  return (
    <SettingsContext.Provider value={{ settings, updateSetting }}>
      {children}
    </SettingsContext.Provider>
  );
};

export const useSettings = (): SettingsContextType => {
  const context = useContext(SettingsContext);
  if (!context) {
    throw new Error("useSettings must be used within a SettingsProvider");
  }
  return context;
};

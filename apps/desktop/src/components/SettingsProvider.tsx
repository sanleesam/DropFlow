import React, { createContext, useContext, useState, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";

// ─── Types & Interfaces ──────────────────────────────────────────────────────

export type AccentColor = "blue" | "indigo" | "purple" | "pink" | "emerald";

export interface TrustedDevice {
  deviceId: string;
  deviceName: string;
  firstSeen: string;
  lastSeen: string;
  publicKey?: string;
  platform: string;
}

export interface Settings {
  accentColor: AccentColor;
  askBeforeOverwrite: boolean;
  autoOpenCompleted: boolean;
  deviceName: string;
  deviceNameMode: "auto" | "custom";
  deviceVisibility: boolean;
  receiveDirectory: string;
  requireConfirmation: boolean;
  autoAcceptTrustedDevices: boolean;
  maxConcurrentTransfers: number;
  darkTheme: boolean;
  reduceAnimations: boolean;
  trustedDevices: TrustedDevice[];
}

interface SettingsContextType {
  settings: Settings;
  updateSetting: <K extends keyof Settings>(key: K, value: Settings[K]) => void;
  addTrustedDevice: (device: TrustedDevice) => Promise<void>;
  removeTrustedDevice: (deviceId: string) => Promise<void>;
  reloadSettings: () => Promise<void>;
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
    buttonGrad: "bg-blue-600 hover:bg-blue-500 text-white font-medium transition-colors",
    sidebarActive: "bg-neutral-800 text-neutral-100 font-medium border-l-2 border-blue-500",
    switchBg: "bg-blue-600",
    switchFocus: "focus:ring-blue-500/40",
    progressFrom: "from-blue-600",
    progressTo: "to-blue-500",
    progressBgDot: "bg-blue-500",
    progressText: "text-blue-400",
    progressIconBg: "bg-blue-500/10",
    progressIconText: "text-blue-400",
    deviceSelected: "bg-blue-500/10 border-blue-500/50 text-white",
    deviceBadgeBg: "bg-blue-500",
    deviceIconBg: "bg-blue-500/15 text-blue-400",
    deviceFocus: "focus-visible:ring-blue-500/50",
    headerLogoGrad: "bg-blue-600",
    dropZoneActive: "border-blue-500/70 bg-blue-500/5",
    dropZoneHover: "border-neutral-800 bg-neutral-900/40 hover:border-neutral-700 hover:bg-neutral-900/60",
    dropZoneIcon: "text-blue-400",
    dropZoneTitle: "text-neutral-200",
    dropZoneOverlay: "pointer-events-none absolute inset-0 rounded-xl bg-blue-500/5",
    buttonShadow: "shadow-sm",
    ringFocus: "focus-visible:ring-blue-500/50 focus-visible:ring-offset-2 focus-visible:ring-offset-[#0b0c0e]",
  },
  indigo: {
    buttonGrad: "bg-indigo-600 hover:bg-indigo-500 text-white font-medium transition-colors",
    sidebarActive: "bg-neutral-800 text-neutral-100 font-medium border-l-2 border-indigo-500",
    switchBg: "bg-indigo-600",
    switchFocus: "focus:ring-indigo-500/40",
    progressFrom: "from-indigo-600",
    progressTo: "to-indigo-500",
    progressBgDot: "bg-indigo-500",
    progressText: "text-indigo-400",
    progressIconBg: "bg-indigo-500/10",
    progressIconText: "text-indigo-400",
    deviceSelected: "bg-indigo-500/10 border-indigo-500/50 text-white",
    deviceBadgeBg: "bg-indigo-500",
    deviceIconBg: "bg-indigo-500/15 text-indigo-400",
    deviceFocus: "focus-visible:ring-indigo-500/50",
    headerLogoGrad: "bg-indigo-600",
    dropZoneActive: "border-indigo-500/70 bg-indigo-500/5",
    dropZoneHover: "border-neutral-800 bg-neutral-900/40 hover:border-neutral-700 hover:bg-neutral-900/60",
    dropZoneIcon: "text-indigo-400",
    dropZoneTitle: "text-neutral-200",
    dropZoneOverlay: "pointer-events-none absolute inset-0 rounded-xl bg-indigo-500/5",
    buttonShadow: "shadow-sm",
    ringFocus: "focus-visible:ring-indigo-500/50 focus-visible:ring-offset-2 focus-visible:ring-offset-[#0b0c0e]",
  },
  purple: {
    buttonGrad: "bg-purple-600 hover:bg-purple-500 text-white font-medium transition-colors",
    sidebarActive: "bg-neutral-800 text-neutral-100 font-medium border-l-2 border-purple-500",
    switchBg: "bg-purple-600",
    switchFocus: "focus:ring-purple-500/40",
    progressFrom: "from-purple-600",
    progressTo: "to-purple-500",
    progressBgDot: "bg-purple-500",
    progressText: "text-purple-400",
    progressIconBg: "bg-purple-500/10",
    progressIconText: "text-purple-400",
    deviceSelected: "bg-purple-500/10 border-purple-500/50 text-white",
    deviceBadgeBg: "bg-purple-500",
    deviceIconBg: "bg-purple-500/15 text-purple-400",
    deviceFocus: "focus-visible:ring-purple-500/50",
    headerLogoGrad: "bg-purple-600",
    dropZoneActive: "border-purple-500/70 bg-purple-500/5",
    dropZoneHover: "border-neutral-800 bg-neutral-900/40 hover:border-neutral-700 hover:bg-neutral-900/60",
    dropZoneIcon: "text-purple-400",
    dropZoneTitle: "text-neutral-200",
    dropZoneOverlay: "pointer-events-none absolute inset-0 rounded-xl bg-purple-500/5",
    buttonShadow: "shadow-sm",
    ringFocus: "focus-visible:ring-purple-500/50 focus-visible:ring-offset-2 focus-visible:ring-offset-[#0b0c0e]",
  },
  pink: {
    buttonGrad: "bg-pink-600 hover:bg-pink-500 text-white font-medium transition-colors",
    sidebarActive: "bg-neutral-800 text-neutral-100 font-medium border-l-2 border-pink-500",
    switchBg: "bg-pink-600",
    switchFocus: "focus:ring-pink-500/40",
    progressFrom: "from-pink-600",
    progressTo: "to-pink-500",
    progressBgDot: "bg-pink-500",
    progressText: "text-pink-400",
    progressIconBg: "bg-pink-500/10",
    progressIconText: "text-pink-400",
    deviceSelected: "bg-pink-500/10 border-pink-500/50 text-white",
    deviceBadgeBg: "bg-pink-500",
    deviceIconBg: "bg-pink-500/15 text-pink-400",
    deviceFocus: "focus-visible:ring-pink-500/50",
    headerLogoGrad: "bg-pink-600",
    dropZoneActive: "border-pink-500/70 bg-pink-500/5",
    dropZoneHover: "border-neutral-800 bg-neutral-900/40 hover:border-neutral-700 hover:bg-neutral-900/60",
    dropZoneIcon: "text-pink-400",
    dropZoneTitle: "text-neutral-200",
    dropZoneOverlay: "pointer-events-none absolute inset-0 rounded-xl bg-pink-500/5",
    buttonShadow: "shadow-sm",
    ringFocus: "focus-visible:ring-pink-500/50 focus-visible:ring-offset-2 focus-visible:ring-offset-[#0b0c0e]",
  },
  emerald: {
    buttonGrad: "bg-emerald-600 hover:bg-emerald-500 text-white font-medium transition-colors",
    sidebarActive: "bg-neutral-800 text-neutral-100 font-medium border-l-2 border-emerald-500",
    switchBg: "bg-emerald-600",
    switchFocus: "focus:ring-emerald-500/40",
    progressFrom: "from-emerald-600",
    progressTo: "to-emerald-500",
    progressBgDot: "bg-emerald-500",
    progressText: "text-emerald-400",
    progressIconBg: "bg-emerald-500/10",
    progressIconText: "text-emerald-400",
    deviceSelected: "bg-emerald-500/10 border-emerald-500/50 text-white",
    deviceBadgeBg: "bg-emerald-500",
    deviceIconBg: "bg-emerald-500/15 text-emerald-400",
    deviceFocus: "focus-visible:ring-emerald-500/50",
    headerLogoGrad: "bg-emerald-600",
    dropZoneActive: "border-emerald-500/70 bg-emerald-500/5",
    dropZoneHover: "border-neutral-800 bg-neutral-900/40 hover:border-neutral-700 hover:bg-neutral-900/60",
    dropZoneIcon: "text-emerald-400",
    dropZoneTitle: "text-neutral-200",
    dropZoneOverlay: "pointer-events-none absolute inset-0 rounded-xl bg-emerald-500/5",
    buttonShadow: "shadow-sm",
    ringFocus: "focus-visible:ring-emerald-500/50 focus-visible:ring-offset-2 focus-visible:ring-offset-[#0b0c0e]",
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
    receiveDirectory: "",
    requireConfirmation: true,
    autoAcceptTrustedDevices: true,
    maxConcurrentTransfers: 3,
    darkTheme: true,
    reduceAnimations: false,
    trustedDevices: [],
  });

  // Dynamically inject CSS variables onto document root based on active accent color
  useEffect(() => {
    const colors: Record<AccentColor, { primary: string; hover: string; soft: string; shadow: string; from: string; to: string }> = {
      blue: { primary: "#2563eb", hover: "#3b82f6", soft: "rgba(37, 99, 235, 0.12)", shadow: "rgba(37, 99, 235, 0.15)", from: "#2563eb", to: "#3b82f6" },
      indigo: { primary: "#4f46e5", hover: "#6366f1", soft: "rgba(79, 70, 229, 0.12)", shadow: "rgba(79, 70, 229, 0.15)", from: "#4f46e5", to: "#6366f1" },
      purple: { primary: "#9333ea", hover: "#a855f7", soft: "rgba(147, 51, 234, 0.12)", shadow: "rgba(147, 51, 234, 0.15)", from: "#9333ea", to: "#a855f7" },
      pink: { primary: "#db2777", hover: "#ec4899", soft: "rgba(219, 39, 119, 0.12)", shadow: "rgba(219, 39, 119, 0.15)", from: "#db2777", to: "#ec4899" },
      emerald: { primary: "#059669", hover: "#10b981", soft: "rgba(5, 150, 105, 0.12)", shadow: "rgba(5, 150, 105, 0.15)", from: "#059669", to: "#10b981" },
    };
    const active = colors[settings.accentColor];
    document.documentElement.style.setProperty("--df-accent", active.primary);
    document.documentElement.style.setProperty("--df-accent-hover", active.hover);
    document.documentElement.style.setProperty("--df-accent-soft", active.soft);
    document.documentElement.style.setProperty("--df-accent-shadow", active.shadow);
    document.documentElement.style.setProperty("--df-accent-from", active.from);
    document.documentElement.style.setProperty("--df-accent-to", active.to);
  }, [settings.accentColor]);

  const reloadSettings = async () => {
    try {
      const appState = await invoke<any>("get_app_state");
      if (appState && appState.settings) {
        const rustSet = appState.settings;
        setSettings((prev) => ({
          ...prev,
          deviceName: rustSet.deviceName || prev.deviceName,
          receiveDirectory: rustSet.receiveDirectory || prev.receiveDirectory,
          accentColor: (rustSet.accentColor as AccentColor) || prev.accentColor,
          requireConfirmation: typeof rustSet.requireConfirmation === "boolean" ? rustSet.requireConfirmation : !rustSet.autoAccept,
          askBeforeOverwrite: typeof rustSet.askBeforeOverwrite === "boolean" ? rustSet.askBeforeOverwrite : prev.askBeforeOverwrite,
          autoOpenCompleted: typeof rustSet.autoOpenCompleted === "boolean" ? rustSet.autoOpenCompleted : prev.autoOpenCompleted,
          autoAcceptTrustedDevices: typeof rustSet.autoAcceptTrustedDevices === "boolean" ? rustSet.autoAcceptTrustedDevices : prev.autoAcceptTrustedDevices,
          maxConcurrentTransfers: rustSet.maxConcurrentTransfers || prev.maxConcurrentTransfers,
          trustedDevices: appState.trustedDevices || [],
        }));
      }
    } catch (e) {
      console.error("[SettingsProvider] Error fetching state:", e);
    }
  };

  useEffect(() => {
    reloadSettings();
  }, []);

  useEffect(() => {
    if (!settings.deviceName) {
      invoke<string>("get_system_computer_name")
        .then((sysName) => {
          setSettings((prev) => {
            if (!prev.deviceName) {
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
  }, [settings.deviceName]);

  const updateSetting = <K extends keyof Settings>(key: K, value: Settings[K]) => {
    setSettings((prev) => {
      const next = { ...prev, [key]: value };
      invoke("save_settings", {
        settings: {
          deviceName: next.deviceName,
          receiveDirectory: next.receiveDirectory,
          autoAccept: !next.requireConfirmation,
          soundNotifications: next.autoOpenCompleted,
          theme: next.darkTheme ? "dark" : "light",
          accentColor: next.accentColor,
          maxConcurrentTransfers: next.maxConcurrentTransfers,
          askBeforeOverwrite: next.askBeforeOverwrite,
          autoOpenCompleted: next.autoOpenCompleted,
          requireConfirmation: next.requireConfirmation,
          autoAcceptTrustedDevices: next.autoAcceptTrustedDevices,
        },
      }).catch(console.error);
      return next;
    });
  };

  const addTrustedDevice = async (device: TrustedDevice) => {
    await invoke("add_trusted_device", { device });
    await reloadSettings();
  };

  const removeTrustedDevice = async (deviceId: string) => {
    await invoke("remove_trusted_device", { deviceId });
    await reloadSettings();
  };

  return (
    <SettingsContext.Provider value={{ settings, updateSetting, addTrustedDevice, removeTrustedDevice, reloadSettings }}>
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

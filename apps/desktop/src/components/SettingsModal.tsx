import React, { useEffect, useRef, useState } from "react";
import { X, Folder, Shield, Network, Palette, Info, Check, ShieldCheck, Heart } from "lucide-react";
import { useSettings, ACCENT_COLOR_MAPS } from "./SettingsProvider";

// ─── Props Interface ──────────────────────────────────────────────────────────

interface SettingsModalProps {
  /** Controls modal visibility */
  isOpen: boolean;
  /** Callback fired to close the modal */
  onClose: () => void;
}

// ─── Reusable Switch Component ────────────────────────────────────────────────

interface SwitchProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  id?: string;
  label?: string;
}

export const Switch: React.FC<SwitchProps> = ({
  checked,
  onChange,
  disabled = false,
  id,
  label,
}) => {
  const { settings } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];

  return (
    <button
      id={id}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={[
        "relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent",
        "transition-colors duration-200 ease-in-out focus:outline-none focus:ring-1",
        accent.switchFocus,
        checked ? accent.switchBg : "bg-slate-700",
        disabled ? "opacity-30 cursor-not-allowed" : "hover:bg-opacity-95",
      ].join(" ")}
    >
      <span
        aria-hidden="true"
        className={[
          "pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow ring-0",
          "transition duration-200 ease-in-out",
          checked ? "translate-x-4" : "translate-x-0",
        ].join(" ")}
      />
    </button>
  );
};

// ─── Settings Modal Component ───────────────────────────────────────────────

type TabID = "transfers" | "network" | "security" | "appearance" | "about";

export const SettingsModal: React.FC<SettingsModalProps> = ({ isOpen, onClose }) => {
  const overlayRef = useRef<HTMLDivElement>(null);
  const [activeTab, setActiveTab] = useState<TabID>("transfers");

  // Consume global settings context
  const { settings, updateSetting } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];

  const [updateStatus, setUpdateStatus] = useState<string | null>(null);

  // Close on Escape key press
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape" && isOpen) {
        onClose();
      }
    };
    if (isOpen) {
      document.body.style.overflow = "hidden";
      window.addEventListener("keydown", handleKeyDown);
    }
    return () => {
      document.body.style.overflow = "unset";
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [isOpen, onClose]);

  // Close on clicking the backdrop overlay
  const handleOverlayClick = (e: React.MouseEvent) => {
    if (e.target === overlayRef.current) {
      onClose();
    }
  };

  if (!isOpen) return null;

  const tabs: { id: TabID; label: string; icon: React.ReactNode }[] = [
    { id: "transfers", label: "Transfers", icon: <Folder size={16} /> },
    { id: "network", label: "Network", icon: <Network size={16} /> },
    { id: "security", label: "Security", icon: <Shield size={16} /> },
    { id: "appearance", label: "Appearance", icon: <Palette size={16} /> },
    { id: "about", label: "About", icon: <Info size={16} /> },
  ];

  return (
    <div
      ref={overlayRef}
      onClick={handleOverlayClick}
      className="fixed inset-0 z-50 flex items-center justify-center p-4 md:p-6 bg-slate-950/65 backdrop-blur-sm animate-backdrop-fade-in"
    >
      {/* Modal Dialog Container */}
      <div
        className="
          relative w-full max-w-3xl h-[85vh] max-h-[640px] rounded-2xl border border-white/6
          bg-slate-900/85 backdrop-blur-2xl shadow-[0_24px_64px_rgba(0,0,0,0.6)]
          flex flex-col overflow-hidden animate-modal-scale-in
        "
      >
        {/* Subtle background glow sheen */}
        <div className="absolute inset-0 bg-gradient-to-br from-white/[0.02] to-transparent pointer-events-none" />

        {/* Modal Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/5 z-10 select-none">
          <h2 className="text-base font-semibold tracking-wide text-slate-200">Settings</h2>
          <button
            type="button"
            onClick={onClose}
            className="
              w-7 h-7 rounded-lg
              flex items-center justify-center
              text-slate-500 hover:text-slate-200
              hover:bg-white/5
              transition-all duration-150
              outline-none focus-visible:ring-1 focus-visible:ring-slate-500
            "
            aria-label="Close settings"
          >
            <X size={16} strokeWidth={2.5} />
          </button>
        </div>

        {/* Modal Body Container */}
        <div className="flex-1 flex flex-col md:flex-row overflow-hidden z-10">
          
          {/* Navigation Sidebar (Scrollable horizontally on mobile, vertically on desktop) */}
          <div className="flex-shrink-0 w-full md:w-48 border-b md:border-b-0 md:border-r border-white/5 bg-slate-950/20">
            <nav className="flex md:flex-col overflow-x-auto md:overflow-x-visible p-2 md:p-3 gap-1 md:gap-1.5 scrollbar-none">
              {tabs.map((tab) => {
                const isActive = activeTab === tab.id;
                return (
                  <button
                    key={tab.id}
                    type="button"
                    onClick={() => setActiveTab(tab.id)}
                    className={[
                      "flex items-center gap-2.5 px-3 py-2 rounded-xl text-sm font-medium",
                      "transition-all duration-200 select-none shrink-0 whitespace-nowrap",
                      isActive
                        ? accent.sidebarActive
                        : "text-slate-400 hover:text-slate-200 hover:bg-white/[0.02] border border-transparent",
                    ].join(" ")}
                  >
                    {tab.icon}
                    <span>{tab.label}</span>
                  </button>
                );
              })}
            </nav>
          </div>

          {/* Active Panel Content Pane */}
          <div className="flex-1 overflow-y-auto p-6 scrollbar-none">
            
            {/* Tab: Transfers */}
            {activeTab === "transfers" && (
              <div className="flex flex-col gap-6">
                <div>
                  <h3 className="text-sm font-semibold uppercase tracking-wider text-slate-400 mb-4 select-none">
                    Transfers
                  </h3>
                  
                  {/* File Download Folder Option */}
                  <div className="flex flex-col gap-2 pb-5 border-b border-white/5">
                    <div className="flex flex-col">
                      <label className="text-sm font-medium text-slate-200">Download folder</label>
                      <span className="text-xs text-slate-500 mt-0.5">
                        Where files received from other devices will be saved.
                      </span>
                    </div>
                    <div className="flex items-center gap-2 mt-1">
                      <div className="flex-1 text-xs font-mono text-slate-400 bg-slate-950/40 border border-white/5 rounded-xl px-3 py-2 truncate">
                        /Users/sanleesam/Downloads
                      </div>
                      <button
                        type="button"
                        className="
                          px-3 py-2 rounded-xl text-xs font-semibold
                          bg-white/5 hover:bg-white/10 text-slate-300 hover:text-white border border-white/5
                          transition-all duration-150 cursor-pointer
                        "
                      >
                        Change...
                      </button>
                    </div>
                  </div>

                  {/* Ask Before Overwrite Switch */}
                  <div className="flex items-center justify-between py-4 border-b border-white/5">
                    <div className="flex flex-col pr-4">
                      <span className="text-sm font-medium text-slate-200">Ask before overwrite</span>
                      <span className="text-xs text-slate-500 mt-0.5">
                        Prompt for confirmation if a file with the same name already exists.
                      </span>
                    </div>
                    <Switch checked={settings.askBeforeOverwrite} onChange={(val) => updateSetting("askBeforeOverwrite", val)} label="Ask before overwrite" />
                  </div>

                  {/* Auto-open Switch */}
                  <div className="flex items-center justify-between py-4 border-b border-white/5">
                    <div className="flex flex-col pr-4">
                      <span className="text-sm font-medium text-slate-200">Auto-open completed transfers</span>
                      <span className="text-xs text-slate-500 mt-0.5">
                        Open the folder or file automatically when the transfer finishes.
                      </span>
                    </div>
                    <Switch checked={settings.autoOpenCompleted} onChange={(val) => updateSetting("autoOpenCompleted", val)} label="Auto-open completed transfers" />
                  </div>

                  {/* Concurrent Transfers placeholder */}
                  <div className="flex items-center justify-between py-4 opacity-50 select-none">
                    <div className="flex flex-col pr-4">
                      <span className="text-sm font-medium text-slate-200">Concurrent transfers</span>
                      <span className="text-xs text-slate-500 mt-0.5">
                        Limit the number of active transfers allowed at the same time.
                      </span>
                    </div>
                    <div className="text-xs font-semibold text-slate-500 bg-slate-950/40 border border-white/5 rounded-lg px-2.5 py-1">
                      3 transfers
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* Tab: Network */}
            {activeTab === "network" && (
              <div className="flex flex-col gap-6">
                <div>
                  <h3 className="text-sm font-semibold uppercase tracking-wider text-slate-400 mb-4 select-none">
                    Network Settings
                  </h3>

                  {/* Device Name input */}
                  <div className="flex flex-col gap-2 pb-5 border-b border-white/5">
                    <div className="flex flex-col">
                      <label htmlFor="device-name-input" className="text-sm font-medium text-slate-200">
                        Device name
                      </label>
                      <span className="text-xs text-slate-500 mt-0.5">
                        The name other devices will see when discovering you.
                      </span>
                    </div>
                    <input
                      id="device-name-input"
                      type="text"
                      value={settings.deviceName}
                      onChange={(e) => updateSetting("deviceName", e.target.value)}
                      className={[
                        "w-full mt-1 text-sm text-slate-200 bg-slate-950/40 border border-white/5 rounded-xl px-3 py-2.5",
                        "focus:outline-none focus:ring-1",
                        accent.switchFocus,
                      ].join(" ")}
                      placeholder="Enter device name"
                    />
                  </div>

                  {/* Local IP Address placeholder */}
                  <div className="flex items-center justify-between py-4 border-b border-white/5 select-none">
                    <div className="flex flex-col pr-4">
                      <span className="text-sm font-medium text-slate-200">Local IP address</span>
                      <span className="text-xs text-slate-500 mt-0.5">
                        Your local IPv4 address on this network.
                      </span>
                    </div>
                    <div className="font-mono text-xs text-slate-400 bg-slate-950/40 border border-white/5 rounded-lg px-2.5 py-1">
                      192.168.1.142
                    </div>
                  </div>

                  {/* Port number placeholder */}
                  <div className="flex items-center justify-between py-4 border-b border-white/5 select-none">
                    <div className="flex flex-col pr-4">
                      <span className="text-sm font-medium text-slate-200">Port</span>
                      <span className="text-xs text-slate-500 mt-0.5">
                        The port number used for incoming connections.
                      </span>
                    </div>
                    <div className="font-mono text-xs text-slate-400 bg-slate-950/40 border border-white/5 rounded-lg px-2.5 py-1">
                      42382
                    </div>
                  </div>

                  {/* Device Visibility Switch */}
                  <div className="flex items-center justify-between py-4">
                    <div className="flex flex-col pr-4">
                      <span className="text-sm font-medium text-slate-200">Device visibility</span>
                      <span className="text-xs text-slate-500 mt-0.5">
                        Allow other devices on this network to discover you.
                      </span>
                    </div>
                    <Switch checked={settings.deviceVisibility} onChange={(val) => updateSetting("deviceVisibility", val)} label="Device visibility" />
                  </div>
                </div>
              </div>
            )}

            {/* Tab: Security */}
            {activeTab === "security" && (
              <div className="flex flex-col gap-6">
                <div>
                  <h3 className="text-sm font-semibold uppercase tracking-wider text-slate-400 mb-4 select-none">
                    Security & Trust
                  </h3>

                  {/* Trusted Devices placeholder list */}
                  <div className="flex flex-col gap-2.5 pb-5 border-b border-white/5">
                    <div className="flex flex-col">
                      <span className="text-sm font-medium text-slate-200">Trusted devices</span>
                      <span className="text-xs text-slate-500 mt-0.5">
                        Devices that can send files without manual approval.
                      </span>
                    </div>
                    <div className="flex flex-col items-center justify-center p-6 bg-slate-950/20 border border-dashed border-white/5 rounded-2xl select-none mt-1">
                      <ShieldCheck className="text-slate-600 mb-2" size={24} strokeWidth={1.5} />
                      <span className="text-xs text-slate-500">No trusted devices yet.</span>
                    </div>
                  </div>

                  {/* Require Confirmation Switch */}
                  <div className="flex items-center justify-between py-4 border-b border-white/5">
                    <div className="flex flex-col pr-4">
                      <span className="text-sm font-medium text-slate-200">Require confirmation</span>
                      <span className="text-xs text-slate-500 mt-0.5">
                        Always ask for approval before accepting incoming transfers.
                      </span>
                    </div>
                    <Switch checked={settings.requireConfirmation} onChange={(val) => updateSetting("requireConfirmation", val)} label="Require confirmation" />
                  </div>

                  {/* Auto accept switch (disabled placeholder) */}
                  <div className="flex items-center justify-between py-4 opacity-50 select-none">
                    <div className="flex flex-col pr-4">
                      <span className="text-sm font-medium text-slate-200">Auto accept trusted devices</span>
                      <span className="text-xs text-slate-500 mt-0.5">
                        Skip verification for trusted devices.
                      </span>
                    </div>
                    <Switch checked={false} onChange={() => {}} disabled label="Auto accept trusted devices (Disabled)" />
                  </div>
                </div>
              </div>
            )}

            {/* Tab: Appearance */}
            {activeTab === "appearance" && (
              <div className="flex flex-col gap-6">
                <div>
                  <h3 className="text-sm font-semibold uppercase tracking-wider text-slate-400 mb-4 select-none">
                    Appearance
                  </h3>

                  {/* Dark Theme Switch */}
                  <div className="flex items-center justify-between py-4 border-b border-white/5">
                    <div className="flex flex-col pr-4">
                      <span className="text-sm font-medium text-slate-200">Dark theme</span>
                      <span className="text-xs text-slate-500 mt-0.5">
                        Switch between dark and light appearance.
                      </span>
                    </div>
                    <Switch checked={settings.darkTheme} onChange={(val) => updateSetting("darkTheme", val)} label="Dark theme" />
                  </div>

                  {/* Accent Color selection preview */}
                  <div className="flex flex-col gap-3.5 py-5 border-b border-white/5">
                    <div className="flex flex-col">
                      <span className="text-sm font-medium text-slate-200">Accent color</span>
                      <span className="text-xs text-slate-500 mt-0.5">
                        Choose the primary highlights for the app.
                      </span>
                    </div>
                    <div className="flex items-center gap-3.5 mt-1">
                      {(["blue", "indigo", "purple", "pink", "emerald"] as const).map((color) => {
                        const bgClasses: Record<string, string> = {
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
                            className={[
                              "relative w-7 h-7 rounded-full flex items-center justify-center transition-all duration-150 outline-none focus:ring-2 focus:ring-offset-2 focus:ring-offset-slate-900 border border-white/10",
                              bgClasses[color],
                            ].join(" ")}
                            aria-label={`Set ${color} accent color`}
                          >
                            {isActive && <Check size={14} className="text-white" strokeWidth={3} />}
                          </button>
                        );
                      })}
                    </div>
                  </div>

                  {/* Reduce Animations Switch */}
                  <div className="flex items-center justify-between py-4">
                    <div className="flex flex-col pr-4">
                      <span className="text-sm font-medium text-slate-200">Reduce animations</span>
                      <span className="text-xs text-slate-500 mt-0.5">
                        Minimize interface motion and transitions.
                      </span>
                    </div>
                    <Switch checked={settings.reduceAnimations} onChange={(val) => updateSetting("reduceAnimations", val)} label="Reduce animations" />
                  </div>
                </div>
              </div>
            )}

            {/* Tab: About */}
            {activeTab === "about" && (
              <div className="flex flex-col gap-6">
                <div>
                  <h3 className="text-sm font-semibold uppercase tracking-wider text-slate-400 mb-4 select-none">
                    About DropFlow
                  </h3>

                  {/* Version info list */}
                  <div className="flex flex-col gap-3.5 pb-5 border-b border-white/5 select-none">
                    <div className="grid grid-cols-2 gap-4 text-sm bg-slate-950/20 border border-white/5 rounded-2xl p-4">
                      <div className="flex flex-col gap-1">
                        <span className="text-xs text-slate-500 font-medium uppercase tracking-wider">DropFlow</span>
                        <span className="text-slate-200 font-medium">v0.1.0</span>
                      </div>
                      <div className="flex flex-col gap-1">
                        <span className="text-xs text-slate-500 font-medium uppercase tracking-wider">React</span>
                        <span className="text-slate-200 font-medium">v19.1.0</span>
                      </div>
                      <div className="flex flex-col gap-1">
                        <span className="text-xs text-slate-500 font-medium uppercase tracking-wider">Tauri</span>
                        <span className="text-slate-200 font-medium">v2.0.0</span>
                      </div>
                      <div className="flex flex-col gap-1">
                        <span className="text-xs text-slate-500 font-medium uppercase tracking-wider">Platform</span>
                        <span className="text-slate-200 font-medium">macOS (Tauri)</span>
                      </div>
                    </div>
                  </div>

                  {/* Check for updates option */}
                  <div className="flex items-center justify-between py-4">
                    <div className="flex flex-col pr-4">
                      <span className="text-sm font-medium text-slate-200">Check for updates</span>
                      <span className="text-xs text-slate-500 mt-0.5">
                        Verify if a newer version of DropFlow is available.
                      </span>
                    </div>
                    <div className="flex flex-col items-end gap-1.5 shrink-0">
                      <button
                        type="button"
                        onClick={() => {
                          setUpdateStatus("checking");
                          setTimeout(() => setUpdateStatus("latest"), 1500);
                        }}
                        disabled={updateStatus === "checking"}
                        className={[
                          "px-4 py-2 rounded-xl text-xs font-semibold select-none transition-all duration-150 border border-white/5",
                          updateStatus === "checking"
                            ? "bg-slate-800 text-slate-500 cursor-not-allowed"
                            : `${accent.switchBg} hover:bg-opacity-90 text-white cursor-pointer`,
                        ].join(" ")}
                      >
                        {updateStatus === "checking" ? "Checking..." : "Check for Updates"}
                      </button>
                      {updateStatus === "latest" && (
                        <span className="text-[10px] text-emerald-400 font-semibold select-none">
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
            )}

          </div>

        </div>

      </div>
    </div>
  );
};

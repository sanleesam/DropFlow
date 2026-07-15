import React, { useEffect, useRef, useState } from "react";
import { X, Folder, Shield, Network, Palette, Info } from "lucide-react";
import { useSettings, ACCENT_COLOR_MAPS } from "./SettingsProvider";
import { TransfersTab } from "./settings/TransfersTab";
import { NetworkTab } from "./settings/NetworkTab";
import { SecurityTab } from "./settings/SecurityTab";
import { AppearanceTab } from "./settings/AppearanceTab";
import { AboutTab } from "./settings/AboutTab";

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
        "relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border border-transparent",
        "transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2",
        accent.switchFocus,
        checked ? accent.switchBg : "bg-slate-700",
        disabled ? "opacity-30 cursor-not-allowed" : "hover:bg-opacity-95",
      ].join(" ")}
    >
      <span
        aria-hidden="true"
        className={[
          "pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow-[0_2px_6px_rgba(0,0,0,.3)] ring-0",
          "transition duration-200 ease-in-out",
          checked ? "translate-x-5" : "translate-x-0",
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
  const { settings } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];

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
      className="fixed inset-0 z-50 flex items-center justify-center bg-[#05070c]/75 p-3 backdrop-blur-md animate-backdrop-fade-in sm:p-6"
    >
      {/* Modal Dialog Container */}
      <div
        className="
          relative flex h-[min(720px,92vh)] w-full max-w-4xl flex-col overflow-hidden rounded-[20px] border border-white/[.12]
          bg-[#10151f]/98 shadow-[0_28px_80px_rgba(0,0,0,.55)]
          animate-modal-scale-in
        "
      >
        {/* Subtle background glow sheen */}
        <div className="pointer-events-none absolute inset-0 bg-gradient-to-br from-[var(--df-accent)]/[0.05] to-transparent" />

        {/* Modal Header */}
        <div className="z-10 flex items-center justify-between border-b border-white/[.08] px-5 py-4 select-none sm:px-6">
          <div>
            <h2 className="text-base font-semibold tracking-tight text-slate-100">Settings</h2>
            <p className="mt-0.5 text-xs text-slate-500">Customize how DropFlow works for you.</p>
          </div>
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
          
          {/* Navigation Sidebar */}
          <div className="w-full flex-shrink-0 border-b border-white/[.08] bg-[#0d121b]/65 md:w-52 md:border-b-0 md:border-r">
            <nav className="flex gap-1 overflow-x-auto p-2 md:flex-col md:gap-1.5 md:p-3 scrollbar-none">
              {tabs.map((tab) => {
                const isActive = activeTab === tab.id;
                return (
                  <button
                    key={tab.id}
                    type="button"
                    onClick={() => setActiveTab(tab.id)}
                    className={[
                      "flex items-center gap-2.5 rounded-[11px] border px-3 py-2 text-sm font-medium",
                      "transition-all duration-200 select-none shrink-0 whitespace-nowrap",
                      isActive
                        ? accent.sidebarActive
                        : "border-transparent text-slate-400 hover:bg-white/[.05] hover:text-slate-200",
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
          <div className="flex-1 overflow-y-auto p-5 scrollbar-none sm:p-7">
            {activeTab === "transfers" && <TransfersTab />}
            {activeTab === "network" && <NetworkTab />}
            {activeTab === "security" && <SecurityTab />}
            {activeTab === "appearance" && <AppearanceTab />}
            {activeTab === "about" && <AboutTab />}
          </div>

        </div>

      </div>
    </div>
  );
};

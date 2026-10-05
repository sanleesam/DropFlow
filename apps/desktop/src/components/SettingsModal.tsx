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
  /** Current number of history items stored */
  historyCount?: number;
  /** Callback fired when clear history is confirmed */
  onClearHistory?: () => void;
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
        "relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border border-transparent",
        "transition-colors duration-150 ease-in-out focus:outline-none focus:ring-2",
        accent.switchFocus,
        checked ? accent.switchBg : "bg-neutral-700",
        disabled ? "opacity-30 cursor-not-allowed" : "hover:opacity-90",
      ].join(" ")}
    >
      <span
        aria-hidden="true"
        className={[
          "pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow-sm ring-0",
          "transition duration-150 ease-in-out",
          checked ? "translate-x-4" : "translate-x-0",
        ].join(" ")}
      />
    </button>
  );
};

// ─── Settings Modal Component ───────────────────────────────────────────────

type TabID = "transfers" | "network" | "security" | "appearance" | "about";

export const SettingsModal: React.FC<SettingsModalProps> = ({
  isOpen,
  onClose,
  historyCount = 0,
  onClearHistory,
}) => {
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
    { id: "transfers", label: "Transfers", icon: <Folder size={15} /> },
    { id: "network", label: "Network", icon: <Network size={15} /> },
    { id: "security", label: "Security", icon: <Shield size={15} /> },
    { id: "appearance", label: "Appearance", icon: <Palette size={15} /> },
    { id: "about", label: "About", icon: <Info size={15} /> },
  ];

  return (
    <div
      ref={overlayRef}
      onClick={handleOverlayClick}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/65 p-3 backdrop-blur-md animate-backdrop-fade-in sm:p-6"
    >
      {/* Modal Dialog Container */}
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Settings"
        className="
          relative flex h-[min(640px,88vh)] w-full max-w-3xl flex-col overflow-hidden rounded-xl border border-white/[0.08]
          bg-[#121418] shadow-2xl
          animate-modal-scale-in
        "
      >
        {/* Modal Header */}
        <div className="z-10 flex items-center justify-between border-b border-white/[0.07] px-5 py-3.5 select-none bg-[#101216]/60 sm:px-6">
          <h2 className="text-sm font-semibold tracking-tight text-neutral-100">Settings</h2>
          <button
            type="button"
            onClick={onClose}
            className="
              w-6 h-6 rounded-md
              flex items-center justify-center
              text-neutral-400 hover:text-neutral-200
              hover:bg-neutral-800
              transition-all duration-150
              outline-none focus-visible:ring-1 focus-visible:ring-neutral-500
            "
            aria-label="Close settings"
          >
            <X size={15} strokeWidth={2} />
          </button>
        </div>

        {/* Modal Body Container */}
        <div className="flex-1 flex flex-col md:flex-row overflow-hidden z-10">
          
          {/* Navigation Sidebar */}
          <div className="w-full flex-shrink-0 border-b border-white/[0.07] bg-[#0e1013] md:w-48 md:border-b-0 md:border-r">
            <nav className="flex gap-1 overflow-x-auto p-2 md:flex-col md:gap-1 md:p-3 scrollbar-none">
              {tabs.map((tab) => {
                const isActive = activeTab === tab.id;
                return (
                  <button
                    key={tab.id}
                    type="button"
                    onClick={() => setActiveTab(tab.id)}
                    className={[
                      "flex items-center gap-2.5 rounded-lg border border-transparent px-3 py-2 text-xs font-medium",
                      "transition-colors duration-150 select-none shrink-0 whitespace-nowrap",
                      isActive
                        ? accent.sidebarActive
                        : "text-neutral-400 hover:bg-neutral-800/50 hover:text-neutral-200",
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
          <div className="flex-1 overflow-y-auto p-5 scrollbar-none sm:p-6 bg-[#121418]">
            {activeTab === "transfers" && (
              <TransfersTab historyCount={historyCount} onClearHistory={onClearHistory} />
            )}
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

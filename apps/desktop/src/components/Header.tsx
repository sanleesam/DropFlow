import React from "react";
import logoImg from "../assets/logo.png";

interface HeaderProps {
  activePage?: "home" | "history";
  onNavigate?: (page: "home" | "history") => void;
  /** Callback fired when the user clicks the Settings button */
  onSettingsClick?: () => void;
}

const Header: React.FC<HeaderProps> = ({ activePage = "home", onNavigate, onSettingsClick }) => {
  return (
    <header className="df-header sticky top-0 z-50 flex items-center justify-between px-5 py-3 border-b border-white/[0.07] bg-[#101216]/80 backdrop-blur-xl">
      {/* Logo + Title */}
      <div
        className="flex items-center gap-2.5 select-none cursor-pointer"
        onClick={() => onNavigate && onNavigate("home")}
      >
        {/* Icon mark */}
        <img
          src={logoImg}
          alt="DropFlow Logo"
          className="h-8 w-auto object-contain flex-shrink-0"
        />

        {/* Wordmark */}
        <div>
          <span className="block text-[15px] font-semibold tracking-tight text-neutral-100">
            DropFlow
          </span>
          <span className="hidden text-[11px] text-neutral-500 sm:block">Private file transfer</span>
        </div>
      </div>

      {/* Native macOS Segmented Control */}
      {onNavigate && (
        <div className="relative inline-flex items-center bg-neutral-900/80 p-0.5 rounded-lg border border-white/[0.08] select-none">
          {/* Sliding active pill indicator */}
          <div
            className="absolute top-0.5 bottom-0.5 left-0.5 w-[calc(50%-2px)] rounded-[5px] bg-neutral-800 border border-white/[0.12] shadow-sm transition-transform duration-150 ease-[cubic-bezier(0.22,1,0.36,1)]"
            style={{
              transform: activePage === "home" ? "translateX(0%)" : "translateX(100%)",
            }}
          />
          <button
            type="button"
            onClick={() => onNavigate("home")}
            className={`relative z-10 w-24 py-1 text-center text-xs font-medium transition-colors duration-150 ${
              activePage === "home" ? "text-white font-semibold" : "text-neutral-400 hover:text-neutral-200"
            }`}
          >
            Dashboard
          </button>
          <button
            type="button"
            onClick={() => onNavigate("history")}
            className={`relative z-10 w-24 py-1 text-center text-xs font-medium transition-colors duration-150 ${
              activePage === "history" ? "text-white font-semibold" : "text-neutral-400 hover:text-neutral-200"
            }`}
          >
            History
          </button>
        </div>
      )}

      {/* Right: Settings */}
      <button
        id="settings-btn"
        type="button"
        aria-label="Open settings"
        onClick={onSettingsClick}
        className="df-icon-button group"
      >
        <svg
          viewBox="0 0 24 24"
          className="h-4.5 w-4.5 transition-transform duration-200 group-hover:rotate-45"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.75"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <circle cx="12" cy="12" r="3" />
          <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z" />
        </svg>
      </button>
    </header>
  );
};

export default Header;

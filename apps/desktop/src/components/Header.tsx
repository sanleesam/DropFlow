import React from "react";
import { useSettings, ACCENT_COLOR_MAPS } from "./SettingsProvider";

interface HeaderProps {
  /** Callback fired when the user clicks the Settings button */
  onSettingsClick?: () => void;
}

const Header: React.FC<HeaderProps> = ({ onSettingsClick }) => {
  const { settings } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];
  return (
    <header className="flex items-center justify-between px-6 py-4 border-b border-white/5 backdrop-blur-xl bg-slate-950/60 sticky top-0 z-50">
      {/* Logo + Title */}
      <div className="flex items-center gap-3 select-none">
        {/* Icon mark */}
        <div className="relative w-8 h-8 flex-shrink-0">
          <div className={`absolute inset-0 rounded-lg bg-gradient-to-br ${accent.headerLogoGrad} opacity-90 shadow-lg`} />
          <svg
            viewBox="0 0 32 32"
            className="absolute inset-0 w-full h-full p-1.5"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
            aria-hidden="true"
          >
            <path
              d="M16 4L4 12V20L16 28L28 20V12L16 4Z"
              stroke="white"
              strokeWidth="2"
              strokeLinejoin="round"
              fill="none"
            />
            <path
              d="M16 4V28M4 12L28 20M28 12L4 20"
              stroke="white"
              strokeWidth="1.5"
              strokeLinecap="round"
              opacity="0.5"
            />
          </svg>
        </div>

        {/* Wordmark */}
        <span className="text-xl font-bold tracking-tight bg-gradient-to-r from-blue-400 via-indigo-400 to-purple-400 bg-clip-text text-transparent">
          DropFlow
        </span>
      </div>

      {/* Right: Settings */}
      <button
        id="settings-btn"
        type="button"
        aria-label="Open settings"
        onClick={onSettingsClick}
        className="group relative w-9 h-9 rounded-xl flex items-center justify-center text-slate-400 transition-all duration-200 hover:text-white hover:bg-white/8 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
      >
        <svg
          viewBox="0 0 24 24"
          className="w-5 h-5 transition-transform duration-300 group-hover:rotate-45"
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

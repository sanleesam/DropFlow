import React from "react";
import { Moon, Sun } from "lucide-react";
import { useSettings } from "../SettingsProvider";

export const CornerActions: React.FC = () => {
  const { settings, updateSetting } = useSettings();
  const letter = (settings.deviceName || "S").trim().charAt(0).toUpperCase() || "S";

  return (
    <div className="df-corner">
      <button
        type="button"
        className="df-moon"
        aria-label={settings.darkTheme ? "Switch to light theme" : "Switch to dark theme"}
        onClick={() => updateSetting("darkTheme", !settings.darkTheme)}
      >
        {settings.darkTheme ? <Sun size={18} strokeWidth={1.75} /> : <Moon size={18} strokeWidth={1.75} />}
      </button>
      <div className="df-avatar" aria-hidden>{letter}</div>
    </div>
  );
};

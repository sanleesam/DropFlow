import React from "react";
import { useSettings, ACCENT_COLOR_MAPS } from "../SettingsProvider";
import { Switch } from "../SettingsModal";

export const NetworkTab: React.FC = () => {
  const { settings, updateSetting } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];

  return (
    <div className="flex flex-col gap-6 animate-[toast-slide-in_0.2s_ease-out]">
      <div>
        <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-4 select-none">
          Network Settings
        </h3>

        {/* Device Name input */}
        <div className="flex flex-col gap-2 pb-5 border-b border-white/5">
          <div className="flex flex-col">
            <label htmlFor="device-name-input" className="text-sm font-semibold text-slate-200">
              Device name
            </label>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              The name other devices will see when discovering you.
            </span>
          </div>
          <input
            id="device-name-input"
            type="text"
            value={settings.deviceName}
            onChange={(e) => {
              updateSetting("deviceName", e.target.value);
              updateSetting("deviceNameMode", "custom");
            }}
            className={`
              w-full mt-2 text-sm text-slate-200 bg-slate-950/40 border border-white/5 rounded-xl px-3.5 py-3
              focus:outline-none focus:ring-1 ${accent.switchFocus}
            `}
            placeholder="Enter device name"
          />
        </div>

        {/* Local IP Address */}
        <div className="flex items-center justify-between py-4.5 border-b border-white/5 select-none">
          <div className="flex flex-col pr-4">
            <span className="text-sm font-semibold text-slate-200">Local IP address</span>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              Your local IPv4 address on this network.
            </span>
          </div>
          <div className="font-mono text-xs text-slate-400 bg-slate-950/40 border border-white/5 rounded-lg px-2.5 py-1">
            192.168.1.142
          </div>
        </div>

        {/* Port number */}
        <div className="flex items-center justify-between py-4.5 border-b border-white/5 select-none">
          <div className="flex flex-col pr-4">
            <span className="text-sm font-semibold text-slate-200">mDNS Port</span>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              The local network port advertised for DropFlow.
            </span>
          </div>
          <div className="font-mono text-xs text-slate-400 bg-slate-950/40 border border-white/5 rounded-lg px-2.5 py-1">
            42382
          </div>
        </div>

        {/* Device Visibility Switch */}
        <div className="flex items-center justify-between py-4.5">
          <div className="flex flex-col pr-4">
            <span className="text-sm font-semibold text-slate-200">Device visibility</span>
            <span className="text-xs text-slate-500 mt-1 leading-normal">
              Allow other devices on this network to discover you.
            </span>
          </div>
          <Switch checked={settings.deviceVisibility} onChange={(val) => updateSetting("deviceVisibility", val)} label="Device visibility" />
        </div>
      </div>
    </div>
  );
};

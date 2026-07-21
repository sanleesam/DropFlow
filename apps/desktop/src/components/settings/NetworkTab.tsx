import React from "react";
import { useSettings } from "../SettingsProvider";
import { Switch } from "../SettingsModal";

export const NetworkTab: React.FC = () => {
  const { settings, updateSetting } = useSettings();

  return (
    <div className="flex flex-col gap-5 animate-[backdrop-fade-in_0.15s_ease-out]">
      <div>
        <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-400 mb-4 select-none">
          Network Settings
        </h3>

        {/* Device Name input */}
        <div className="flex flex-col gap-2 pb-4 border-b border-white/[0.07]">
          <div className="flex flex-col">
            <label htmlFor="device-name-input" className="text-xs font-semibold text-neutral-200">
              Device name
            </label>
            <span className="text-xs text-neutral-500 mt-0.5">
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
              w-full mt-1.5 text-xs text-neutral-200 bg-neutral-950/60 border border-white/[0.08] rounded-lg px-3 py-2
              focus:outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500/40
            `}
            placeholder="Enter device name"
          />
        </div>

        {/* Local IP Address */}
        <div className="flex items-center justify-between py-3.5 border-b border-white/[0.07] select-none">
          <div className="flex flex-col pr-4">
            <span className="text-xs font-semibold text-neutral-200">Local IP address</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              Your local IPv4 address on this network.
            </span>
          </div>
          <div className="font-mono text-xs text-neutral-400 bg-neutral-950/60 border border-white/[0.08] rounded-md px-2.5 py-1">
            192.168.1.142
          </div>
        </div>

        {/* Port number */}
        <div className="flex items-center justify-between py-3.5 border-b border-white/[0.07] select-none">
          <div className="flex flex-col pr-4">
            <span className="text-xs font-semibold text-neutral-200">mDNS Port</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              The local network port advertised for DropFlow.
            </span>
          </div>
          <div className="font-mono text-xs text-neutral-400 bg-neutral-950/60 border border-white/[0.08] rounded-md px-2.5 py-1">
            42382
          </div>
        </div>

        {/* Device Visibility Switch */}
        <div className="flex items-center justify-between py-3.5">
          <div className="flex flex-col pr-4">
            <span className="text-xs font-semibold text-neutral-200">Device visibility</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              Allow other devices on this network to discover you.
            </span>
          </div>
          <Switch checked={settings.deviceVisibility} onChange={(val) => updateSetting("deviceVisibility", val)} label="Device visibility" />
        </div>
      </div>
    </div>
  );
};

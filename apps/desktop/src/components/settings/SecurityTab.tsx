import React from "react";
import { ShieldCheck, Trash2, Smartphone, Monitor } from "lucide-react";
import { useSettings } from "../SettingsProvider";
import { Switch } from "../SettingsModal";

function normalizePlatform(platform?: string): "Desktop" | "Mobile" {
  const p = (platform || "").toLowerCase();

  // Desktop always wins if both substrings exist (legacy compatibility e.g. "Desktop/Mobile" -> Desktop)
  if (p.includes("desktop") || p.includes("mac") || p.includes("windows") || p.includes("linux")) {
    return "Desktop";
  }

  if (p.includes("mobile") || p.includes("android") || p.includes("phone")) {
    return "Mobile";
  }

  return "Desktop";
}

export const SecurityTab: React.FC = () => {
  const { settings, updateSetting, removeTrustedDevice } = useSettings();
  const trustedDevices = settings.trustedDevices || [];

  return (
    <div className="flex flex-col gap-5 animate-[backdrop-fade-in_0.15s_ease-out]">
      <div>
        <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-400 mb-4 select-none">
          Security & Trust
        </h3>

        {/* Trusted Devices list */}
        <div className="flex flex-col gap-2 pb-4 border-b border-white/[0.07]">
          <div className="flex flex-col">
            <span className="text-xs font-semibold text-neutral-200">Trusted devices</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              Devices that can send files without manual approval.
            </span>
          </div>

          {trustedDevices.length === 0 ? (
            <div className="flex flex-col items-center justify-center p-6 bg-neutral-950/40 border border-dashed border-white/[0.08] rounded-xl select-none mt-2">
              <ShieldCheck className="text-neutral-500 mb-2 w-6 h-6" strokeWidth={1.5} />
              <span className="text-xs font-medium text-neutral-300">No trusted devices yet</span>
              <span className="text-[11px] text-neutral-500 mt-0.5 text-center max-w-[220px]">
                You can trust devices directly from incoming transfer prompts.
              </span>
            </div>
          ) : (
            <div className="flex flex-col gap-2 mt-2">
              {trustedDevices.map((device) => {
                const platformLabel = normalizePlatform(device.platform);
                const isMobile = platformLabel === "Mobile";

                return (
                  <div
                    key={device.deviceId}
                    className="flex items-center justify-between p-3 bg-neutral-950/60 border border-white/[0.08] rounded-xl"
                  >
                    <div className="flex items-center gap-3">
                      <div className="p-2 rounded-lg bg-neutral-800 text-neutral-300">
                        {isMobile ? <Smartphone className="w-4 h-4 text-blue-400" /> : <Monitor className="w-4 h-4 text-blue-400" />}
                      </div>
                      <div className="flex flex-col">
                        <span className="text-xs font-semibold text-neutral-200">{device.deviceName}</span>
                        <span className="text-[11px] text-neutral-500">
                          {platformLabel} • First seen {device.firstSeen || "recently"}
                        </span>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => removeTrustedDevice(device.deviceId)}
                      className="p-2 rounded-lg text-neutral-400 hover:text-red-400 hover:bg-red-500/10 transition-colors cursor-pointer"
                      title="Untrust device"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Require Confirmation Switch */}
        <div className="flex items-center justify-between py-3.5 border-b border-white/[0.07]">
          <div className="flex flex-col pr-4">
            <span className="text-xs font-semibold text-neutral-200">Require confirmation</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              Always ask for approval before accepting incoming transfers.
            </span>
          </div>
          <Switch checked={settings.requireConfirmation} onChange={(val) => updateSetting("requireConfirmation", val)} label="Require confirmation" />
        </div>

        {/* Auto accept trusted devices switch */}
        <div className="flex items-center justify-between py-3.5 select-none">
          <div className="flex flex-col pr-4">
            <span className="text-xs font-semibold text-neutral-200">Auto accept trusted devices</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              Skip verification prompts for devices you have previously trusted.
            </span>
          </div>
          <Switch checked={settings.autoAcceptTrustedDevices} onChange={(val) => updateSetting("autoAcceptTrustedDevices", val)} label="Auto accept trusted devices" />
        </div>
      </div>
    </div>
  );
};

import React from "react";
import { ShieldCheck } from "lucide-react";
import { useSettings } from "../SettingsProvider";
import { Switch } from "../SettingsModal";

export const SecurityTab: React.FC = () => {
  const { settings, updateSetting } = useSettings();

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
          <div className="flex flex-col items-center justify-center p-6 bg-neutral-950/40 border border-dashed border-white/[0.08] rounded-xl select-none mt-2">
            <ShieldCheck className="text-neutral-500 mb-2 w-6 h-6" strokeWidth={1.5} />
            <span className="text-xs font-medium text-neutral-300">No trusted devices yet</span>
            <span className="text-[11px] text-neutral-500 mt-0.5 text-center max-w-[220px]">
              You can trust devices directly from incoming transfer prompts.
            </span>
          </div>
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

        {/* Auto accept switch (disabled placeholder) */}
        <div className="flex items-center justify-between py-3.5 opacity-50 select-none">
          <div className="flex flex-col pr-4">
            <span className="text-xs font-semibold text-neutral-200">Auto accept trusted devices</span>
            <span className="text-xs text-neutral-500 mt-0.5">
              Skip verification prompts for devices you have previously trusted.
            </span>
          </div>
          <Switch checked={false} onChange={() => {}} disabled label="Auto accept trusted devices (Disabled)" />
        </div>
      </div>
    </div>
  );
};
